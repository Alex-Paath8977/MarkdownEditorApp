package com.example.markdowneditor;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;

public class DocumentViewerActivity extends AppCompatActivity {
    private LinearLayout container;
    private MarkdownParser parser;
    private FloatingActionButton fabEdit;
    private String content;

    private final ActivityResultLauncher<Intent> editLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String editedContent = result.getData().getStringExtra("content");
                    if (editedContent != null) {
                        updateContent(editedContent);
                    }
                }
            });

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        container = findViewById(R.id.container);
        fabEdit = findViewById(R.id.fab_edit);
        container.removeAllViews();

        parser = new MarkdownParser(this);

        content = getIntent().getStringExtra("content");
        boolean canEdit = getIntent().getBooleanExtra("can_edit", false);

        if (content == null || content.trim().isEmpty()) {
            content = "# Ошибка\nНе удалось загрузить содержимое";
            showError("Документ пуст или не был загружен");
        } else {
            displayMarkdown(content);
        }

        setupEditButton(canEdit);
    }

    private void setupEditButton(boolean canEdit) {
        if (canEdit) {
            fabEdit.setVisibility(View.VISIBLE);
            fabEdit.setOnClickListener(v -> launchEditor());
        } else {
            fabEdit.setVisibility(View.GONE);
        }
    }

    private void launchEditor() {
        if (content == null) {
            Toast.makeText(this, "Нет содержимого для редактирования", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent editIntent = new Intent(this, DocumentEditorActivity.class);
        editIntent.putExtra("content", content);
        editLauncher.launch(editIntent);
    }

    private void displayMarkdown(String markdown) {
        try {
            container.removeAllViews();
            List<View> views = parser.parseMarkdownText(markdown);
            for (View view : views) {
                container.addView(view);
            }
        } catch (Exception e) {
            showError("Ошибка при обработке Markdown: " + e.getMessage());
            Log.e("MarkdownError", "Ошибка парсинга", e);
        }
    }

    private void updateContent(String newContent) {
        this.content = newContent;
        displayMarkdown(newContent);
    }

    private void showError(String message) {
        TextView errorView = new TextView(this);
        errorView.setText(message);
        errorView.setTextColor(Color.RED);
        container.addView(errorView);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (parser != null) {
            parser.cleanup();
        }
    }
}