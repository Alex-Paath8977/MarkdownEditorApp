package com.example.markdowneditor;

import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.URLUtil;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {
    private static final int FILE_PICKER_REQUEST = 1;
    private static final int EDIT_REQUEST_CODE = 2;
    private static final String TAG = "MainActivity";

    private EditText urlInput;
    private ProgressBar progressBar;
    private Button btnLoadFile, btnLoadUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        initView();
    }

    private void initView() {
        urlInput = findViewById(R.id.et_url);
        progressBar = findViewById(R.id.progress_bar);
        btnLoadFile = findViewById(R.id.btn_load_file);
        btnLoadUrl = findViewById(R.id.btn_load_url);

        btnLoadFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/*");
            startActivityForResult(intent, FILE_PICKER_REQUEST);
        });

        btnLoadUrl.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (URLUtil.isValidUrl(url)) {
                new DownloadTask().execute(url);
            } else {
                urlInput.setError("Некорректный URL");
            }
        });
    }

    private class DownloadTask extends AsyncTask<String, Void, DownloadResult> {
        @Override
        protected void onPreExecute() {
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        protected DownloadResult doInBackground(String... urls) {
            HttpURLConnection connection = null;
            try {
                String url = convertGoogleDriveUrl(urls[0]);
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                connection.setConnectTimeout(15000);
                connection.connect();

                // Проверяем Content-Type
                String contentType = connection.getContentType();
                if (contentType == null || !contentType.startsWith("text/")) {
                    return new DownloadResult(null, "Файл не является текстовым (Content-Type: " + contentType + ")");
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {
                    StringBuilder result = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.append(line).append("\n");
                    }
                    return new DownloadResult(result.toString(), null);
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка загрузки", e);
                return new DownloadResult(null, "Ошибка загрузки: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private String convertGoogleDriveUrl(String originalUrl) {
            return originalUrl.replace(
                    "https://drive.google.com/file/d/",
                    "https://drive.google.com/uc?export=download&id="
            ).split("/view")[0];
        }

        @Override
        protected void onPostExecute(DownloadResult result) {
            progressBar.setVisibility(View.GONE);
            if (result.content != null) {
                openViewerActivity(result.content, true);
            } else {
                Toast.makeText(MainActivity.this,
                        result.errorMessage != null ? result.errorMessage : "Неизвестная ошибка",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private static class DownloadResult {
        String content;
        String errorMessage;

        DownloadResult(String content, String errorMessage) {
            this.content = content;
            this.errorMessage = errorMessage;
        }
    }

    private void openViewerActivity(String content, boolean canEdit) {
        Intent intent = new Intent(this, DocumentViewerActivity.class);
        intent.putExtra("content", content);
        intent.putExtra("can_edit", canEdit);
        startActivityForResult(intent, EDIT_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_PICKER_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(getContentResolver().openInputStream(uri)))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                openViewerActivity(content.toString(), true);
            } catch (Exception e) {
                Toast.makeText(this, "Ошибка чтения файла: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        else if (requestCode == EDIT_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            String editedContent = data.getStringExtra("content");
            if (editedContent != null) {
                openViewerActivity(editedContent, true);
            }
        }
    }
}