package com.smsrelay;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int SMS_PERMISSION_CODE = 100;

    private EditText backendUrlEdit;
    private EditText targetNumberEdit;
    private EditText adminPhoneEdit;
    private Switch activeSwitch;
    private Button saveButton;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("sms_relay_prefs", MODE_PRIVATE);

        backendUrlEdit = findViewById(R.id.backendUrlEdit);
        targetNumberEdit = findViewById(R.id.targetNumberEdit);
        adminPhoneEdit = findViewById(R.id.adminPhoneEdit);
        activeSwitch = findViewById(R.id.activeSwitch);
        saveButton = findViewById(R.id.saveButton);

        loadSettings();
        checkPermissions();

        saveButton.setOnClickListener(v -> saveSettings());

        activeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("is_active", isChecked).apply();
            if (isChecked) {
                startBackgroundService();
                Toast.makeText(this, "SMS Relay Aktif", Toast.LENGTH_SHORT).show();
            } else {
                stopBackgroundService();
                Toast.makeText(this, "SMS Relay Pasif", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadSettings() {
        String backendUrl = prefs.getString("backend_url", "");
        String targetNumber = prefs.getString("target_number", "5664");
        String adminPhone = prefs.getString("admin_phone", "");
        boolean isActive = prefs.getBoolean("is_active", false);

        backendUrlEdit.setText(backendUrl);
        targetNumberEdit.setText(targetNumber);
        adminPhoneEdit.setText(adminPhone);
        activeSwitch.setChecked(isActive);

        if (isActive) {
            startBackgroundService();
        }
    }

    private void saveSettings() {
        String backendUrl = backendUrlEdit.getText().toString().trim();
        String targetNumber = targetNumberEdit.getText().toString().trim();
        String adminPhone = adminPhoneEdit.getText().toString().trim();

        if (backendUrl.isEmpty()) {
            Toast.makeText(this, "Backend URL boş olamaz!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (targetNumber.isEmpty()) {
            Toast.makeText(this, "Hedef numara boş olamaz!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (adminPhone.isEmpty()) {
            Toast.makeText(this, "Admin telefon numarası boş olamaz!", Toast.LENGTH_SHORT).show();
            return;
        }

        prefs.edit()
                .putString("backend_url", backendUrl)
                .putString("target_number", targetNumber)
                .putString("admin_phone", adminPhone)
                .apply();

        Toast.makeText(this, "Ayarlar kaydedildi!", Toast.LENGTH_SHORT).show();

        if (activeSwitch.isChecked()) {
            stopBackgroundService();
            startBackgroundService();
        }
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.RECEIVE_SMS,
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.READ_SMS,
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.FOREGROUND_SERVICE
                    },
                    SMS_PERMISSION_CODE);
        }
    }

    private void startBackgroundService() {
        Intent serviceIntent = new Intent(this, MessageCheckService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopBackgroundService() {
        Intent serviceIntent = new Intent(this, MessageCheckService.class);
        stopService(serviceIntent);
    }
}