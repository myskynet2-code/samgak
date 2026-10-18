package com.myskynet2.talkflow;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/*
 * 대화 흐름판
 * - 화면: assets/index.html (웹뷰)
 * - 녹음: 안드로이드 음성 인식. 한 마디 끝날 때마다 멈추므로 계속 다시 켠다.
 * - 분석: Claude API 를 앱 안에서 직접 호출하고, 받는 대로 화면에 흘려보낸다.
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 1;
    // 음성 인식 다시 켤 때 나는 '띵' 소리를 막으려고 잠시 끄는 소리 종류
    // (알림·시스템 소리는 벨소리 모드와 묶인 폰이 많아 건드리지 않음)
    private static final int[] MUTE_STREAMS = { AudioManager.STREAM_MUSIC };

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private SpeechRecognizer sr;
    private SharedPreferences prefs;

    private volatile boolean wantListening = false; // 사용자가 녹음을 켜 둔 상태인가
    private int session = 0;               // 오래된 콜백 무시용 번호
    private int netFails = 0;
    private int softFails = 0;             // 말 없음 외의 연속 실패 횟수
    private long lastEvent = 0;            // 음성 인식에서 마지막으로 소식이 온 시각
    private boolean watchdogOn = false;
    private volatile int askId = 0;        // 분석 요청 번호 (취소·중복 방지)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("talkflow", MODE_PRIVATE);
        restoreVolume(); // 지난번에 앱이 비정상 종료돼서 소리가 꺼진 채 남았으면 되돌림

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setBackgroundColor(Color.parseColor("#121614"));
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    // ───────── 화면(자바스크립트) ↔ 앱 연결 ─────────
    // 이 메서드들은 웹뷰의 별도 스레드에서 불리므로, 음성 인식은 반드시 메인 스레드로 넘긴다.
    private class Bridge {
        @JavascriptInterface
        public void start() { main.post(MainActivity.this::requestStart); }

        @JavascriptInterface
        public void stop() { main.post(() -> stopListening("")); }

        // 요청 번호를 돌려줌. 화면은 이 번호가 아닌 응답은 버린다.
        @JavascriptInterface
        public int ask(String key, String body) {
            final int id = ++askId;
            new Thread(() -> doAsk(id, key, body)).start();
            return id;
        }

        @JavascriptInterface
        public void cancelAsk() { askId++; }
    }

    private void js(String fn, String... args) {
        StringBuilder b = new StringBuilder("window.").append(fn).append("&&window.").append(fn).append("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) b.append(',');
            b.append(JSONObject.quote(args[i] == null ? "" : args[i]));
        }
        b.append(");");
        final String code = b.toString();
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    // ───────── 녹음 ─────────
    private void requestStart() {
        if (wantListening) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // 허용 창이 뜨는 동안 화면이 잠깐 멈춤(onPause) 상태가 되므로, 허용된 뒤에 시작한다
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        beginListening();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            beginListening();
        } else {
            js("onRecState", "error", "마이크 허용이 필요합니다. 휴대폰 설정 → 애플리케이션 → 대화 흐름판 → 권한 → 마이크 허용");
        }
    }

    private void beginListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("onRecState", "error", "이 휴대폰에서 음성 인식 서비스를 찾을 수 없습니다. 플레이 스토어에서 'Google' 앱을 설치하거나 업데이트해 주세요.");
            return;
        }
        wantListening = true;
        netFails = 0;
        softFails = 0;
        lastEvent = System.currentTimeMillis();
        if (!watchdogOn) { watchdogOn = true; main.postDelayed(watchdog, 5000); }
        muteBeep();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        js("onRecState", "on", "");
        startRecognizer();
    }

    // 같은 인식기를 다시 쓰면 끊기는 틈이 짧다. 오류가 났을 때만 새로 만든다.
    private void startRecognizer() { startRecognizer(false); }

    private void startRecognizer(boolean fresh) {
        if (!wantListening) return;
        if (fresh) destroyRecognizer();
        final int my = ++session;
        lastEvent = System.currentTimeMillis();
        try {
            if (sr == null) sr = SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(new Listener(my));
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            // 말이 멈춰도 조금 더 기다리게 (지원 안 하는 폰은 무시함)
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
            sr.startListening(i);
        } catch (Exception e) {
            destroyRecognizer();
            countSoftFail();
            scheduleRestart(600, true);
        }
    }

    private void scheduleRestart(long ms) { scheduleRestart(ms, false); }

    private void scheduleRestart(long ms, boolean fresh) {
        final int my = session;
        main.postDelayed(() -> { if (wantListening && my == session) startRecognizer(fresh); }, ms);
    }

    // 인식기가 아무 소식 없이 멈춰 버리는 폰이 있어서, 15초 넘게 조용하면 새로 켠다
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!wantListening) { watchdogOn = false; return; }
            if (System.currentTimeMillis() - lastEvent > 15000) startRecognizer(true);
            main.postDelayed(this, 5000);
        }
    };

    private void countSoftFail() {
        softFails++;
        if (softFails == 25) js("onRecState", "warn", "음성 인식이 자꾸 끊깁니다. 계속 다시 시도 중…");
        if (softFails >= 60) stopListening("음성 인식이 계속 실패해서 멈췄습니다. 앱을 껐다 켜거나 'Google' 앱을 업데이트해 주세요.");
    }

    private void destroyRecognizer() {
        if (sr != null) {
            try { sr.cancel(); } catch (Exception ignored) { }
            try { sr.destroy(); } catch (Exception ignored) { }
            sr = null;
        }
    }

    private void stopListening(String msg) {
        boolean was = wantListening;
        wantListening = false;
        session++;
        destroyRecognizer();
        unmuteBeep();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (was || !msg.isEmpty()) js("onRecState", "off", msg);
    }

    private class Listener implements RecognitionListener {
        private final int my;
        Listener(int my) { this.my = my; }
        private boolean stale() {
            if (my != session || !wantListening) return true;
            lastEvent = System.currentTimeMillis();
            return false;
        }

        @Override public void onReadyForSpeech(Bundle params) {
            if (stale()) return;
            js("onRecState", "on", "");
        }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { if (my == session) lastEvent = System.currentTimeMillis(); }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override public void onPartialResults(Bundle b) {
            if (stale()) return;
            String t = first(b);
            if (t != null) js("onPartial", t);
        }

        @Override public void onResults(Bundle b) {
            if (stale()) return;
            session++; // 이 인식은 끝. 늦게 오는 오류 콜백은 무시
            netFails = 0;
            softFails = 0;
            String t = first(b);
            if (t != null && !t.trim().isEmpty()) js("onFinal", t);
            else js("onPartial", "");
            scheduleRestart(250);
        }

        @Override public void onError(int error) {
            if (stale()) return;
            session++;
            switch (error) {
                case 9: // 권한 없음
                    stopListening("마이크 허용이 필요합니다. 휴대폰 설정에서 마이크 권한을 켜 주세요.");
                    return;
                case 12: // 언어 지원 안 함
                case 13: // 언어 받기 안 됨
                    stopListening("한국어 음성 인식이 준비되지 않았습니다. 휴대폰 설정에서 'Google 음성 인식' 언어에 한국어를 추가해 주세요.");
                    return;
                case 1:  // 네트워크 시간 초과
                case 2:  // 네트워크
                case 4:  // 서버
                case 10: // 요청 너무 많음
                case 11: // 서버 연결 끊김
                    netFails++;
                    if (netFails > 8) {
                        stopListening("인터넷 연결이 불안정해 녹음을 멈췄습니다. 연결 확인 후 다시 눌러 주세요.");
                        return;
                    }
                    js("onRecState", "warn", "연결이 불안정합니다. 다시 붙는 중…");
                    scheduleRestart(Math.min(800L * netFails, 5000L), true);
                    return;
                case 6: // 말 없음
                case 7: // 알아들은 말 없음
                    js("onPartial", "");
                    scheduleRestart(250);
                    return;
                default: // 바쁨(8), 오디오(3), 기타(5): 인식기를 새로 만들어 다시
                    js("onPartial", "");
                    countSoftFail();
                    if (wantListening) scheduleRestart(error == 8 ? 700 : 400, true);
            }
        }

        private String first(Bundle b) {
            if (b == null) return null;
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return (r == null || r.isEmpty()) ? null : r.get(0);
        }
    }

    // ───────── '띵' 소리 막기 (녹음 중에만) ─────────
    private void muteBeep() {
        if (!prefs.getString("muted", "").isEmpty()) return; // 이미 꺼 둔 기록이 있으면 덮어쓰지 않음
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        StringBuilder done = new StringBuilder();
        for (int st : MUTE_STREAMS) {
            try {
                if (am.isStreamMute(st)) continue; // 원래 꺼져 있던 건 건드리지 않음
                am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0);
                if (done.length() > 0) done.append(',');
                done.append(st);
            } catch (Exception ignored) { } // 방해 금지 모드 등에서 거부될 수 있음
        }
        prefs.edit().putString("muted", done.toString()).apply();
    }

    private void unmuteBeep() { restoreVolume(); }

    private void restoreVolume() {
        String m = prefs.getString("muted", "");
        if (m.isEmpty()) return;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        for (String p : m.split(",")) {
            try { am.adjustStreamVolume(Integer.parseInt(p.trim()), AudioManager.ADJUST_UNMUTE, 0); }
            catch (Exception ignored) { }
        }
        prefs.edit().putString("muted", "").apply();
    }

    // ───────── 분석 (Claude API, 받는 대로 흘려보내기) ─────────
    private void doAsk(int id, String key, String body) {
        HttpURLConnection c = null;
        String stop = "";
        try {
            c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("anthropic-version", "2023-06-01");
            c.setRequestProperty("accept", "text/event-stream");
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }

            int code = c.getResponseCode();
            if (code != 200) {
                String detail = readAll(c.getErrorStream());
                String msg;
                try { msg = new JSONObject(detail).getJSONObject("error").optString("message", ""); }
                catch (Exception e) { msg = ""; }
                String nice;
                if (code == 401) nice = "키가 맞지 않습니다. 설정에서 키를 확인해 주세요.";
                else if (code == 400 && msg.toLowerCase().contains("credit")) nice = "충전 잔액이 없습니다. console.anthropic.com 에서 충전해 주세요.";
                else if (code == 429 || code == 529) nice = "요청이 몰렸습니다. 잠시 뒤 다시 눌러 주세요.";
                else nice = "오류 " + code + (msg.isEmpty() ? "" : ": " + msg);
                if (id == askId) js("onAnaError", String.valueOf(id), nice);
                return;
            }

            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (id != askId) return; // 취소됨
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                JSONObject ev;
                try { ev = new JSONObject(data); } catch (Exception e) { continue; }
                String type = ev.optString("type");
                if ("content_block_delta".equals(type)) {
                    JSONObject d = ev.optJSONObject("delta");
                    if (d != null && "text_delta".equals(d.optString("type"))) js("onAnaDelta", String.valueOf(id), d.optString("text"));
                } else if ("message_delta".equals(type)) {
                    JSONObject d = ev.optJSONObject("delta");
                    if (d != null) stop = d.optString("stop_reason", stop);
                } else if ("error".equals(type)) {
                    JSONObject e = ev.optJSONObject("error");
                    if (id == askId) js("onAnaError", String.valueOf(id), "분석 중 끊김: " + (e == null ? "" : e.optString("message")));
                    return;
                }
            }
            if (id == askId) js("onAnaDone", String.valueOf(id), stop);
        } catch (Exception e) {
            if (id == askId) js("onAnaError", String.valueOf(id), "인터넷 연결을 확인해 주세요. (" + e.getClass().getSimpleName() + ")");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) b.append(l);
            return b.toString();
        } catch (Exception e) { return ""; }
    }

    // ───────── 화면 생명주기 ─────────
    @Override
    protected void onPause() {
        super.onPause();
        // 다른 앱으로 가거나 화면이 꺼지면 음성 인식이 막히므로 깔끔하게 멈추고 알린다
        if (wantListening) stopListening("화면을 벗어나서 녹음이 멈췄습니다. 다시 누르면 이어서 기록합니다.");
    }

    @Override
    protected void onDestroy() {
        stopListening("");
        askId++;
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 실수로 뒤로 가기 눌러 앱이 닫히지 않게: 녹음 중이면 무시
        if (wantListening) return;
        super.onBackPressed();
    }
}
