package com.smsrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";
    private static final int MESSAGE_TIMEOUT = 600000; // 10 dakika - mesajlar arası timeout
    private static final int COLLECT_DELAY = 15000;    // 15 saniye - SMS parçalarını toplama

    private static Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable timeoutRunnable = null;
    private static Runnable collectRunnable = null;

    private static boolean waitingForMessages = false;
    private static int expectedMessageCount = 0;
    private static Map<Integer, String> receivedMessages = new HashMap<>();

    // SMS parçalarını toplama
    private static StringBuilder currentMessageBuffer = new StringBuilder();
    private static int lastDetectedMessageNum = 0;
    private static int lastDetectedTotalNum = 0;

    private static final Pattern MESSAGE_PATTERN = Pattern.compile("Mesaj\\s*\\((\\d+)/(\\d+)\\)");

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null || !intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences("sms_relay_prefs", Context.MODE_PRIVATE);
        boolean isActive = prefs.getBoolean("is_active", false);
        String targetNumber = prefs.getString("target_number", "5664");

        if (!isActive) {
            return;
        }

        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus != null) {
                for (Object pdu : pdus) {
                    SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
                    String sender = sms.getDisplayOriginatingAddress();
                    String message = sms.getDisplayMessageBody();

                    Log.d(TAG, "SMS parçası alındı - Gönderen: " + sender);

                    if (sender.contains("5664") || sender.contains("TRAMER") ||
                            normalizePhoneNumber(sender).equals(normalizePhoneNumber(targetNumber))) {

                        Log.d(TAG, "5664'ten parça geldi: " + message.substring(0, Math.min(50, message.length())) + "...");

                        if (MessageCheckService.currentQueryId != null) {
                            collectSmsPart(context, message);
                        }
                    }
                }
            }
        }
    }

    private void collectSmsPart(Context context, String part) {
        // "Mesaj (X/Y)" pattern'i var mı kontrol et
        Matcher matcher = MESSAGE_PATTERN.matcher(part);

        if (matcher.find()) {
            // Yeni bir ana mesaj başlıyor
            int msgNum = Integer.parseInt(matcher.group(1));
            int totalNum = Integer.parseInt(matcher.group(2));

            Log.d(TAG, "Yeni mesaj başlığı bulundu: " + msgNum + "/" + totalNum);

            // Önceki buffer'da veri varsa kaydet
            if (currentMessageBuffer.length() > 0 && lastDetectedMessageNum > 0) {
                saveBufferedMessage();
            }

            // Yeni mesaj için buffer başlat
            currentMessageBuffer = new StringBuilder();
            currentMessageBuffer.append(part);
            lastDetectedMessageNum = msgNum;
            lastDetectedTotalNum = totalNum;

            // Toplam mesaj sayısını güncelle
            if (totalNum > expectedMessageCount) {
                expectedMessageCount = totalNum;
            }
        } else {
            // Bu bir devam parçası, buffer'a ekle
            if (currentMessageBuffer.length() > 0) {
                currentMessageBuffer.append(" ").append(part);
                Log.d(TAG, "Parça buffer'a eklendi. Buffer uzunluk: " + currentMessageBuffer.length());
            }
        }

        // Collect timer'ı sıfırla (her parçada)
        resetCollectTimer(context.getApplicationContext());
    }

    private void resetCollectTimer(Context context) {
        // Önceki collect timer'ı iptal et
        if (collectRunnable != null) {
            handler.removeCallbacks(collectRunnable);
        }

        // 15 saniye sonra buffer'ı işle
        collectRunnable = () -> {
            Log.d(TAG, "15 saniye doldu, buffer işleniyor...");
            processCollectedBuffer(context);
        };

        handler.postDelayed(collectRunnable, COLLECT_DELAY);
    }

    private void processCollectedBuffer(Context context) {
        // Son buffer'ı kaydet
        if (currentMessageBuffer.length() > 0 && lastDetectedMessageNum > 0) {
            saveBufferedMessage();
        }

        Log.d(TAG, "Toplanan mesaj sayısı: " + receivedMessages.size() + "/" + expectedMessageCount);

        // Ana timeout timer'ı başlat/sıfırla
        resetMainTimer(context);

        // Tüm mesajlar geldi mi kontrol et
        if (expectedMessageCount > 0 && receivedMessages.size() >= expectedMessageCount) {
            Log.d(TAG, "TÜM MESAJLAR TOPLANDI!");
            cancelAllTimers();
            sendCombinedMessage(context, true);
        }
    }

    private void saveBufferedMessage() {
        String fullMessage = currentMessageBuffer.toString().trim();

        // Duplicate kontrolü
        if (!receivedMessages.containsKey(lastDetectedMessageNum)) {
            receivedMessages.put(lastDetectedMessageNum, fullMessage);
            Log.d(TAG, "Mesaj " + lastDetectedMessageNum + " kaydedildi. Uzunluk: " + fullMessage.length());
        } else {
            Log.d(TAG, "Mesaj " + lastDetectedMessageNum + " zaten var, atlanıyor");
        }

        // Buffer'ı temizle
        currentMessageBuffer = new StringBuilder();
    }

    private void resetMainTimer(Context context) {
        // Önceki timer'ı iptal et
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
        }

        waitingForMessages = true;

        Log.d(TAG, "Ana timer sıfırlandı. 10dk bekleniyor. Alınan: " + receivedMessages.size() + "/" + expectedMessageCount);

        timeoutRunnable = () -> {
            int missing = expectedMessageCount - receivedMessages.size();
            Log.e(TAG, "TIMEOUT! 10 dakika doldu. " + missing + " mesaj eksik");
            handleTimeout(context);
        };

        handler.postDelayed(timeoutRunnable, MESSAGE_TIMEOUT);
    }

    private void cancelAllTimers() {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        if (collectRunnable != null) {
            handler.removeCallbacks(collectRunnable);
            collectRunnable = null;
        }
    }

    private void sendCombinedMessage(Context context, boolean isSuccess) {
        String userPhone = MessageCheckService.currentUserPhone;
        String queryId = MessageCheckService.currentQueryId;
        String backendUrl = MessageCheckService.currentBackendUrl;
        String vehicleId = MessageCheckService.currentVehicleId;

        if (isSuccess && !receivedMessages.isEmpty()) {
            // Mesajları sırayla birleştir
            StringBuilder combined = new StringBuilder();

            for (int i = 1; i <= expectedMessageCount; i++) {
                if (receivedMessages.containsKey(i)) {
                    if (combined.length() > 0) {
                        combined.append("\n\n");
                    }
                    combined.append(receivedMessages.get(i));
                }
            }

            String finalMessage = combined.toString();
            Log.d(TAG, "Birleştirilmiş mesaj hazır. Uzunluk: " + finalMessage.length());

            // Kullanıcıya gönder
            if (userPhone != null && !userPhone.isEmpty()) {
                sendSmsToUser(userPhone, finalMessage);
                Log.d(TAG, "Mesaj kullanıcıya gönderildi: " + userPhone);
            }

            // Backend'e başarılı bildir
            notifyBackendSuccess(backendUrl, queryId);

            // Service'e bildir
            Intent serviceIntent = new Intent(context, MessageCheckService.class);
            serviceIntent.setAction("QUERY_COMPLETED");
            serviceIntent.putExtra("success", true);
            context.startService(serviceIntent);

            Log.d(TAG, "Sorgu BAŞARILI! Plaka: " + vehicleId);
        }

        resetState();
    }

    private void handleTimeout(Context context) {
        String userPhone = MessageCheckService.currentUserPhone;
        String queryId = MessageCheckService.currentQueryId;
        String backendUrl = MessageCheckService.currentBackendUrl;
        String vehicleId = MessageCheckService.currentVehicleId;

        SharedPreferences prefs = context.getSharedPreferences("sms_relay_prefs", Context.MODE_PRIVATE);
        String adminPhone = prefs.getString("admin_phone", "");

        // Gelen mesajları yine de gönder
        if (!receivedMessages.isEmpty()) {
            StringBuilder combined = new StringBuilder();
            for (int i = 1; i <= Math.max(expectedMessageCount, receivedMessages.size() + 1); i++) {
                if (receivedMessages.containsKey(i)) {
                    if (combined.length() > 0) {
                        combined.append("\n\n");
                    }
                    combined.append(receivedMessages.get(i));
                }
            }

            if (userPhone != null && !userPhone.isEmpty() && combined.length() > 0) {
                sendSmsToUser(userPhone, combined.toString());
                Log.d(TAG, "Kısmi mesaj kullanıcıya gönderildi");
            }
        }

        // Admin'e hata bildirimi
        if (!adminPhone.isEmpty()) {
            StringBuilder adminMsg = new StringBuilder();
            adminMsg.append("HATA!\n");
            adminMsg.append("Eksik mesaj - ").append(userPhone).append(" numarasinin sorgulamasi tamamlanamadi.\n");
            adminMsg.append("Plaka: ").append(vehicleId).append("\n");
            adminMsg.append("Alinan: ").append(receivedMessages.size()).append("/").append(expectedMessageCount);

            sendSmsToUser(adminPhone, adminMsg.toString());
            Log.d(TAG, "Admin'e timeout bildirimi gönderildi");
        }

        // Kullanıcıya bilgi
        if (userPhone != null && !userPhone.isEmpty()) {
            String infoMsg = "Not: Sorgulama sonucu eksik olabilir. Detay icin destek hattini arayiniz.";
            sendSmsToUser(userPhone, infoMsg);
        }

        notifyBackendFailed(backendUrl, queryId);

        Intent serviceIntent = new Intent(context, MessageCheckService.class);
        serviceIntent.setAction("QUERY_COMPLETED");
        serviceIntent.putExtra("success", false);
        context.startService(serviceIntent);

        resetState();
    }

    private void resetState() {
        cancelAllTimers();
        waitingForMessages = false;
        expectedMessageCount = 0;
        lastDetectedMessageNum = 0;
        lastDetectedTotalNum = 0;
        receivedMessages.clear();
        currentMessageBuffer = new StringBuilder();
        Log.d(TAG, "State sıfırlandı, sonraki sorguya hazır");
    }

    private void sendSmsToUser(String phoneNumber, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            ArrayList<String> parts = smsManager.divideMessage(message);

            if (parts.size() > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
                Log.d(TAG, "Çok parçalı SMS gönderildi: " + phoneNumber + " (" + parts.size() + " parça)");
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null);
                Log.d(TAG, "SMS gönderildi: " + phoneNumber);
            }
        } catch (Exception e) {
            Log.e(TAG, "SMS gönderilemedi: " + e.getMessage());
        }
    }

    private void notifyBackendSuccess(String backendUrl, String queryId) {
        new Thread(() -> {
            try {
                if (backendUrl == null || queryId == null) return;

                JSONObject json = new JSONObject();
                json.put("query_id", queryId);

                OkHttpClient client = new OkHttpClient();
                RequestBody body = RequestBody.create(
                        json.toString(),
                        MediaType.parse("application/json")
                );

                Request request = new Request.Builder()
                        .url(backendUrl + "/api/query/result-received")
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                Log.d(TAG, "Backend başarılı: " + response.code());
                response.close();
            } catch (Exception e) {
                Log.e(TAG, "Backend hatası: " + e.getMessage());
            }
        }).start();
    }

    private void notifyBackendFailed(String backendUrl, String queryId) {
        new Thread(() -> {
            try {
                if (backendUrl == null || queryId == null) return;

                JSONObject json = new JSONObject();
                json.put("query_id", queryId);
                json.put("status", "timeout");

                OkHttpClient client = new OkHttpClient();
                RequestBody body = RequestBody.create(
                        json.toString(),
                        MediaType.parse("application/json")
                );

                Request request = new Request.Builder()
                        .url(backendUrl + "/api/query/result-failed")
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                Log.d(TAG, "Backend timeout: " + response.code());
                response.close();
            } catch (Exception e) {
                Log.e(TAG, "Backend hatası: " + e.getMessage());
            }
        }).start();
    }

    private String normalizePhoneNumber(String number) {
        if (number == null) return "";
        String normalized = number.replaceAll("[^0-9]", "");
        if (normalized.startsWith("90")) normalized = normalized.substring(2);
        if (normalized.startsWith("0")) normalized = normalized.substring(1);
        return normalized;
    }
}