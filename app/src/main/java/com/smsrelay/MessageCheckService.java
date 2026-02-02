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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
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

    private static final int CHECK_INTERVAL = 5000;
    private static final int WAIT_BETWEEN_QUERIES = 30000;
    private static final int TIMEOUT = 120000;
    private static final int HOURLY_STATUS_INTERVAL = 3600000; // 1 saat

    private Handler handler;
    private Runnable checkMessagesRunnable;
    private Runnable hourlyStatusRunnable;
    private boolean isRunning = false;
    public static boolean isProcessingQuery = false;

    public static String currentQueryId = null;
    public static String currentUserPhone = null;
    public static String currentVehicleId = null;
    public static String currentBackendUrl = null;
    public static boolean responseReceived = false;
    public static String receivedResponse = null;

    // İstatistikler
    private static int successCount = 0;
    private static int failCount = 0;

    private Queue<PendingQuery> queryQueue = new LinkedList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service oluşturuldu");
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("Başlatılıyor..."));

        handler = new Handler(Looper.getMainLooper());

        // Sorgu kontrol runnable
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

        // Saatlik durum bildirimi runnable
        hourlyStatusRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    sendHourlyStatus();
                    handler.postDelayed(this, HOURLY_STATUS_INTERVAL);
                }
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "QUERY_COMPLETED".equals(intent.getAction())) {
            Log.d(TAG, "SmsReceiver'dan sinyal alındı - işlem tamamlandı");

            boolean success = intent.getBooleanExtra("success", false);
            if (success) {
                successCount++;
            } else {
                failCount++;
            }

            responseReceived = true;
            receivedResponse = intent.getStringExtra("response");

            updateNotification("Son sorgu: " + (success ? "Başarılı" : "Başarısız") +
                    " | Toplam: " + successCount + "/" + (successCount + failCount));

            clearCurrentQuery();
            isProcessingQuery = false;

            handler.postDelayed(() -> processNextQuery(), WAIT_BETWEEN_QUERIES);

            return START_STICKY;
        }

        Log.d(TAG, "Service başlatıldı");
        isRunning = true;

        // Sorgu kontrolünü başlat
        handler.post(checkMessagesRunnable);

        // Saatlik durum bildirimini başlat (ilk bildirim 1 saat sonra)
        handler.postDelayed(hourlyStatusRunnable, HOURLY_STATUS_INTERVAL);

        // Başlangıç bildirimi gönder
        sendStartupStatus();

        return START_STICKY;
    }

    private void sendStartupStatus() {
        SharedPreferences prefs = getSharedPreferences("sms_relay_prefs", MODE_PRIVATE);
        String adminPhone = prefs.getString("admin_phone", "");

        if (!adminPhone.isEmpty()) {
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
            String time = sdf.format(new Date());

            String statusMsg = "SMS Relay AKTIF\n" +
                    "Baslangic: " + time + "\n" +
                    "Durum: Calisiyor";

            sendSmsToAdmin(adminPhone, statusMsg);
            Log.d(TAG, "Başlangıç bildirimi gönderildi");
        }
    }

    private void sendHourlyStatus() {
        SharedPreferences prefs = getSharedPreferences("sms_relay_prefs", MODE_PRIVATE);
        String adminPhone = prefs.getString("admin_phone", "");

        if (!adminPhone.isEmpty()) {
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
            String time = sdf.format(new Date());

            String statusMsg = "SMS Relay DURUM\n" +
                    "Saat: " + time + "\n" +
                    "Basarili: " + successCount + "\n" +
                    "Basarisiz: " + failCount + "\n" +
                    "Kuyruk: " + queryQueue.size() + "\n" +
                    "Durum: Aktif";

            sendSmsToAdmin(adminPhone, statusMsg);
            Log.d(TAG, "Saatlik durum bildirimi gönderildi");
        }
    }

    private void sendSmsToAdmin(String phoneNumber, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            Log.d(TAG, "Admin SMS gönderildi: " + phoneNumber);
        } catch (Exception e) {
            Log.e(TAG, "Admin SMS gönderilemedi: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service durduruluyor");
        isRunning = false;
        handler.removeCallbacksAndMessages(null);

        // Kapanış bildirimi
        SharedPreferences prefs = getSharedPreferences("sms_relay_prefs", MODE_PRIVATE);
        String adminPhone = prefs.getString("admin_phone", "");
        if (!adminPhone.isEmpty()) {
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
            String time = sdf.format(new Date());

            String statusMsg = "SMS Relay KAPANDI\n" +
                    "Saat: " + time + "\n" +
                    "Toplam Basarili: " + successCount + "\n" +
                    "Toplam Basarisiz: " + failCount;

            sendSmsToAdmin(adminPhone, statusMsg);
        }
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
                .setContentTitle("SMS Relay Aktif")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
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
            updateNotification("Bekleniyor... | Başarılı: " + successCount + " Başarısız: " + failCount);
            Log.d(TAG, "Kuyruk boş");
            return;
        }

        isProcessingQuery = true;
        PendingQuery query = queryQueue.poll();

        if (query == null) {
            isProcessingQuery = false;
            return;
        }

        currentQueryId = query.queryId;
        currentUserPhone = query.userPhone;
        currentVehicleId = query.vehicleId;
        currentBackendUrl = query.backendUrl;
        responseReceived = false;
        receivedResponse = null;

        Log.d(TAG, "İşleniyor: " + query.queryId + " | Plaka: " + query.vehicleId);
        updateNotification("Sorgulanıyor: " + query.vehicleId);

        SharedPreferences prefs = getSharedPreferences("sms_relay_prefs", MODE_PRIVATE);
        String targetNumber = prefs.getString("target_number", "5664");
        sendSms(targetNumber, query.smsMessage);

        Log.d(TAG, "5664'e gönderildi: " + query.smsMessage);

        handler.postDelayed(() -> {
            if (!responseReceived && currentQueryId != null && currentQueryId.equals(query.queryId)) {
                Log.e(TAG, "TIMEOUT! Cevap gelmedi: " + query.queryId);
                handleTimeout();
            }
        }, TIMEOUT);
    }

    private void handleTimeout() {
        SharedPreferences prefs = getSharedPreferences("sms_relay_prefs", MODE_PRIVATE);
        String adminPhone = prefs.getString("admin_phone", "");

        String errorMessage = "Arac sorgulama sonucu alinamadi. Lutfen daha sonra tekrar deneyiniz.";
        sendSmsToUser(currentUserPhone, errorMessage);

        if (!adminPhone.isEmpty()) {
            String adminMessage = "SORGU BASARISIZ!\nPlaka: " + currentVehicleId + "\nTelefon: " + currentUserPhone;
            sendSmsToUser(adminPhone, adminMessage);
        }

        notifyBackendFailed();

        failCount++;
        updateNotification("HATA: " + currentVehicleId + " | Başarılı: " + successCount + " Başarısız: " + failCount);

        clearCurrentQuery();
        isProcessingQuery = false;

        handler.postDelayed(() -> processNextQuery(), WAIT_BETWEEN_QUERIES);
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
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            }
            Log.d(TAG, "SMS gönderildi: " + phoneNumber);
        } catch (Exception e) {
            Log.e(TAG, "SMS gönderilemedi: " + e.getMessage());
        }
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