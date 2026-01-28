package com.smsrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MessageCheckService extends Service {
    private static final String TAG = "MessageCheckService";
    private static final String CHANNEL_ID = "SMSRelayChannel";
    private static final int NOTIFICATION_ID = 1;

    // Zamanlama sabitleri
    private static final int CHECK_INTERVAL = 5000;         // Backend kontrol: 5 saniye
    private static final int WAIT_FOR_REPLY = 20000;        // 5664 cevap bekleme: 20 saniye
    private static final int WAIT_BETWEEN_QUERIES = 10000;  // Sorgular arası: 10 saniye
    private static final int TIMEOUT = 60000;               // Timeout: 60 saniye

    private Handler handler;
    private Runnable checkMessagesRunnable;
    private boolean isRunning = false;
    private boolean isProcessingQuery = false;

    // Mevcut işlenen sorgu bilgisi
    public static String currentQueryId = null;
    public static String currentUserPhone = null;
    public static String currentVehicleId = null;
    public static String currentBackendUrl = null;
    public static boolean responseReceived = false;
    public static String receivedResponse = null;

    private Queue<PendingQuery> queryQueue = new LinkedList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service oluşturuldu");
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("Başlatılıyor..."));

        handler = new Handler(Looper.getMainLooper());
        checkMessagesRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    if (!isProcessingQuery) {
                        checkForPendingQueries();
                    }
                    handler.postDelayed(this, CHECK_INTERVAL);
                }
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service başlatıldı");
        isRunning = true;
        handler.post(checkMessagesRunnable);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service durduruluyor");
        isRunning = false;
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SMS Relay Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Araç sorgulama için SMS kontrolü");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Araç Sorgulama Aktif")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(text));
        }
    }

    private void checkForPendingQueries() {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences("sms_relay_prefs", MODE_PRIVATE);
                String backendUrl = prefs.getString("backend_url", "");

                if (backendUrl.isEmpty()) {
                    return;
                }

                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder()
                        .url(backendUrl + "/api/queries/pending-5664")
                        .get()
                        .build();

                Response response = client.newCall(request).execute();

                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    JSONArray queries = new JSONArray(responseBody);

                    if (queries.length() > 0) {
                        Log.d(TAG, "Backend'den " + queries.length() + " sorgu alındı");

                        for (int i = 0; i < queries.length(); i++) {
                            JSONObject query = queries.getJSONObject(i);
                            String queryId = query.getString("id");
                            String smsMessage = query.getString("sms_message");
                            String userPhone = query.getString("user_phone");
                            String vehicleId = query.optString("vehicle_id", smsMessage);

                            // Kuyruğa ekle (eğer zaten yoksa)
                            boolean exists = false;
                            for (PendingQuery pq : queryQueue) {
                                if (pq.queryId.equals(queryId)) {
                                    exists = true;
                                    break;
                                }
                            }

                            if (!exists && !queryId.equals(currentQueryId)) {
                                queryQueue.offer(new PendingQuery(queryId, smsMessage, userPhone, vehicleId, backendUrl));
                                Log.d(TAG, "Kuyruğa eklendi: " + queryId);
                            }
                        }

                        // İşlem yoksa başlat
                        if (!isProcessingQuery && !queryQueue.isEmpty()) {
                            handler.post(() -> processNextQuery());
                        }
                    }
                }

                response.close();

            } catch (Exception e) {
                Log.e(TAG, "Sorgu kontrolünde hata: " + e.getMessage());
            }
        }).start();
    }

    private void processNextQuery() {
        if (queryQueue.isEmpty()) {
            isProcessingQuery = false;
            updateNotification("Bekleniyor...");
            Log.d(TAG, "Kuyruk boş");
            return;
        }

        isProcessingQuery = true;
        PendingQuery query = queryQueue.poll();

        if (query == null) {
            isProcessingQuery = false;
            return;
        }

        // Mevcut sorgu bilgilerini kaydet
        currentQueryId = query.queryId;
        currentUserPhone = query.userPhone;
        currentVehicleId = query.vehicleId;
        currentBackendUrl = query.backendUrl;
        responseReceived = false;
        receivedResponse = null;

        Log.d(TAG, "İşleniyor: " + query.queryId + " | Plaka: " + query.vehicleId);
        updateNotification("Sorgulanıyor: " + query.vehicleId);

        // 1. 5664'e SMS gönder
        SharedPreferences prefs = getSharedPreferences("sms_relay_prefs", MODE_PRIVATE);
        String targetNumber = prefs.getString("target_number", "5664");
        sendSms(targetNumber, query.smsMessage);

        Log.d(TAG, "5664'e gönderildi: " + query.smsMessage + " | 20sn bekleniyor...");

        // 2. 20 saniye bekle (cevap toplama süresi)
        handler.postDelayed(() -> {
            checkResponseAndProceed();
        }, WAIT_FOR_REPLY);

        // 3. 60 saniye timeout
        handler.postDelayed(() -> {
            if (!responseReceived && currentQueryId != null && currentQueryId.equals(query.queryId)) {
                Log.e(TAG, "TIMEOUT! 60 saniye içinde cevap gelmedi: " + query.queryId);
                handleTimeout();
            }
        }, TIMEOUT);
    }

    private void checkResponseAndProceed() {
        if (responseReceived && receivedResponse != null) {
            // Cevap geldi, kullanıcıya gönder
            Log.d(TAG, "Cevap alındı, kullanıcıya gönderiliyor: " + currentUserPhone);
            sendSmsToUser(currentUserPhone, receivedResponse);
            notifyBackendSuccess();
            updateNotification("Sonuç gönderildi: " + currentVehicleId);
        } else {
            Log.d(TAG, "20sn doldu, henüz cevap yok. Timeout bekleniyor...");
        }
    }

    private void handleTimeout() {
        SharedPreferences prefs = getSharedPreferences("sms_relay_prefs", MODE_PRIVATE);
        String adminPhone = prefs.getString("admin_phone", "");

        // Kullanıcıya hata mesajı
        String errorMessage = "Araç sorgulama sonucu alınamadı. Lütfen daha sonra tekrar deneyiniz. Ücret iadesi için destek@hasarsorgulama.com adresine başvurabilirsiniz.";
        sendSmsToUser(currentUserPhone, errorMessage);

        // Admin'e bildirim
        if (!adminPhone.isEmpty()) {
            String adminMessage = "SORGU BAŞARISIZ!\nPlaka: " + currentVehicleId + "\nTelefon: " + currentUserPhone + "\nSorgu ID: " + currentQueryId;
            sendSmsToUser(adminPhone, adminMessage);
            Log.d(TAG, "Admin'e bildirim gönderildi: " + adminPhone);
        }

        // Backend'e başarısız bildir
        notifyBackendFailed();

        updateNotification("HATA: " + currentVehicleId + " sonuç alınamadı");

        // Temizle ve sonraki sorguya geç
        clearCurrentQuery();

        // 10 saniye bekle ve sonraki sorguya geç
        handler.postDelayed(() -> processNextQuery(), WAIT_BETWEEN_QUERIES);
    }

    public void onResponseReceived(String response) {
        if (currentQueryId != null && !responseReceived) {
            responseReceived = true;
            receivedResponse = response;
            Log.d(TAG, "Cevap alındı: " + response.substring(0, Math.min(50, response.length())) + "...");

            // Kullanıcıya gönder
            sendSmsToUser(currentUserPhone, response);
            notifyBackendSuccess();
            updateNotification("Sonuç gönderildi: " + currentVehicleId);

            // Temizle
            clearCurrentQuery();

            // 10 saniye bekle ve sonraki sorguya geç
            handler.postDelayed(() -> processNextQuery(), WAIT_BETWEEN_QUERIES);
        }
    }

    private void clearCurrentQuery() {
        currentQueryId = null;
        currentUserPhone = null;
        currentVehicleId = null;
        responseReceived = false;
        receivedResponse = null;
    }

    private void sendSms(String phoneNumber, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            Log.d(TAG, "SMS gönderildi: " + phoneNumber + " -> " + message);
        } catch (Exception e) {
            Log.e(TAG, "SMS gönderilemedi: " + e.getMessage());
        }
    }

    private void sendSmsToUser(String phoneNumber, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            ArrayList<String> parts = smsManager.divideMessage(message);

            if (parts.size() > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
                Log.d(TAG, "Çok parçalı SMS gönderildi: " + phoneNumber);
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null);
                Log.d(TAG, "SMS gönderildi: " + phoneNumber);
            }
        } catch (Exception e) {
            Log.e(TAG, "SMS gönderilemedi: " + e.getMessage());
        }
    }

    private void notifyBackendSuccess() {
        new Thread(() -> {
            try {
                if (currentBackendUrl == null || currentQueryId == null) return;

                JSONObject json = new JSONObject();
                json.put("query_id", currentQueryId);

                OkHttpClient client = new OkHttpClient();
                RequestBody body = RequestBody.create(
                        json.toString(),
                        MediaType.parse("application/json")
                );

                Request request = new Request.Builder()
                        .url(currentBackendUrl + "/api/query/result-received")
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                Log.d(TAG, "Backend'e başarılı bildirildi: " + response.code());
                response.close();
            } catch (Exception e) {
                Log.e(TAG, "Backend bildirimi hatası: " + e.getMessage());
            }
        }).start();
    }

    private void notifyBackendFailed() {
        new Thread(() -> {
            try {
                if (currentBackendUrl == null || currentQueryId == null) return;

                JSONObject json = new JSONObject();
                json.put("query_id", currentQueryId);
                json.put("status", "failed");

                OkHttpClient client = new OkHttpClient();
                RequestBody body = RequestBody.create(
                        json.toString(),
                        MediaType.parse("application/json")
                );

                Request request = new Request.Builder()
                        .url(currentBackendUrl + "/api/query/result-failed")
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                Log.d(TAG, "Backend'e başarısız bildirildi: " + response.code());
                response.close();
            } catch (Exception e) {
                Log.e(TAG, "Backend bildirimi hatası: " + e.getMessage());
            }
        }).start();
    }

    private static class PendingQuery {
        String queryId;
        String smsMessage;
        String userPhone;
        String vehicleId;
        String backendUrl;

        PendingQuery(String queryId, String smsMessage, String userPhone, String vehicleId, String backendUrl) {
            this.queryId = queryId;
            this.smsMessage = smsMessage;
            this.userPhone = userPhone;
            this.vehicleId = vehicleId;
            this.backendUrl = backendUrl;
        }
    }
}