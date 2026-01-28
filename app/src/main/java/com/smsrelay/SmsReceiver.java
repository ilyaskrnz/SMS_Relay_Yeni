package com.smsrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";
    private static final int COLLECT_TIME = 5000; // 5 saniye mesaj toplama

    private static List<String> collectedMessages = new ArrayList<>();
    private static Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable processRunnable = null;

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

                    Log.d(TAG, "SMS alındı - Gönderen: " + sender + ", Mesaj: " + message);

                    // 5664'ten gelen mesajları yakala
                    if (sender.contains("5664") || sender.contains("TRAMER") ||
                            normalizePhoneNumber(sender).equals(normalizePhoneNumber(targetNumber))) {

                        Log.d(TAG, "5664'ten cevap geldi!");

                        // Mevcut işlenen sorgu var mı?
                        if (MessageCheckService.currentQueryId != null) {
                            collectMessage(context, message);
                        }
                    }
                }
            }
        }
    }

    private void collectMessage(Context context, String message) {
        collectedMessages.add(message);
        Log.d(TAG, "Mesaj toplandı (" + collectedMessages.size() + "): " + message);

        // Önceki timer'ı iptal et
        if (processRunnable != null) {
            handler.removeCallbacks(processRunnable);
        }

        // 5 saniye sonra tüm mesajları birleştir ve gönder
        processRunnable = () -> {
            if (!collectedMessages.isEmpty()) {
                StringBuilder combined = new StringBuilder();
                for (int i = 0; i < collectedMessages.size(); i++) {
                    combined.append(collectedMessages.get(i));
                    if (i < collectedMessages.size() - 1) {
                        combined.append(" ");
                    }
                }

                String finalMessage = combined.toString();
                Log.d(TAG, "Birleştirilmiş mesaj: " + finalMessage);

                // Service'e bildir
                Intent serviceIntent = new Intent(context, MessageCheckService.class);
                serviceIntent.setAction("RESPONSE_RECEIVED");
                serviceIntent.putExtra("response", finalMessage);
                context.startService(serviceIntent);

                // Veya doğrudan static method çağır
                MessageCheckService service = null;
                // Service instance'a erişim için broadcast kullan

                // Static değişkenleri güncelle
                MessageCheckService.responseReceived = true;
                MessageCheckService.receivedResponse = finalMessage;

                collectedMessages.clear();
            }
        };

        handler.postDelayed(processRunnable, COLLECT_TIME);
    }

    private String normalizePhoneNumber(String number) {
        if (number == null) return "";
        String normalized = number.replaceAll("[^0-9]", "");
        if (normalized.startsWith("90")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("0")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}