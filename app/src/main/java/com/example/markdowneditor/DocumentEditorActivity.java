package com.example.markdowneditor;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class DocumentEditorActivity extends AppCompatActivity {
    private EditText editor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        editor = findViewById(R.id.editor);
        String content = getIntent().getStringExtra("content");

        if (!TextUtils.isEmpty(content)) {
            editor.setText(content);
            editor.setSelection(content.length());
        }

        Button btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> {
            String editedText = editor.getText().toString();
            if (TextUtils.isEmpty(editedText)) {
                Toast.makeText(this, "Документ не может быть пустым", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent resultIntent = new Intent();
            resultIntent.putExtra("content", editedText);
            setResult(RESULT_OK, resultIntent);
            finish();
        });
    }
}