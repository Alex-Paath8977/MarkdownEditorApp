package com.example.markdowneditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.util.LruCache;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * Класс для парсинга Markdown-текста и преобразования его в Android View элементы
 */
public class MarkdownParser {
    private static final String LOG_TAG = "MarkdownParser";
    private final Context applicationContext;
    private final LruCache<String, Bitmap> imageCache;
    private final List<ImageLoadingTask> activeImageLoadingTasks;
    private String baseDocumentUrl = "";

    public MarkdownParser(Context context) {
        this.applicationContext = context.getApplicationContext();
        final int maxAvailableMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxAvailableMemory / 8;
        this.imageCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        this.activeImageLoadingTasks = new ArrayList<>();
    }

    /**
     * Устанавливает базовый URL документа для разрешения относительных ссылок
     */
    public void setBaseDocumentUrl(String baseUrl) {
        this.baseDocumentUrl = baseUrl;
    }

    /**
     * Преобразует Markdown-текст в список View элементов
     */
    public List<View> parseMarkdownText(String markdownContent) {
        List<View> parsedViews = new ArrayList<>();
        String[] documentLines = markdownContent.split("\n");
        boolean isInsideCodeBlock = false;
        boolean isInsideTable = false;
        List<String[]> tableRowsCollection = new ArrayList<>();
        StringBuilder codeBlockContent = new StringBuilder();

        for (String currentLine : documentLines) {
            if (currentLine.trim().startsWith("```")) {
                if (isInsideCodeBlock) {
                    parsedViews.add(createCodeBlockView(codeBlockContent.toString()));
                    codeBlockContent.setLength(0);
                    isInsideCodeBlock = false;
                } else {
                    isInsideCodeBlock = true;
                }
                continue;
            }

            if (isInsideCodeBlock) {
                codeBlockContent.append(currentLine).append("\n");
                continue;
            }

            if (currentLine.trim().isEmpty()) {
                if (isInsideTable) {
                    parsedViews.add(createTableView(tableRowsCollection));
                    tableRowsCollection.clear();
                    isInsideTable = false;
                }
                continue;
            }

            if (currentLine.contains("|")) {
                if (!isInsideTable && containsTableHeaderSeparator(currentLine)) {
                    isInsideTable = true;
                }

                if (isInsideTable) {
                    tableRowsCollection.add(parseTableRow(currentLine));
                    continue;
                }
            } else if (isInsideTable) {
                parsedViews.add(createTableView(tableRowsCollection));
                tableRowsCollection.clear();
                isInsideTable = false;
            }

            if (currentLine.startsWith("# ")) {
                parsedViews.add(createHeadingView(currentLine.substring(2), 1));
            }
            else if (currentLine.startsWith("## ")) {
                parsedViews.add(createHeadingView(currentLine.substring(3), 2));
            }
            else if (currentLine.startsWith("### ")) {
                parsedViews.add(createHeadingView(currentLine.substring(4), 3));
            }
            else if (currentLine.startsWith("#### ")) {
                parsedViews.add(createHeadingView(currentLine.substring(5), 4));
            }
            else if (currentLine.startsWith("##### ")) {
                parsedViews.add(createHeadingView(currentLine.substring(6), 5));
            }
            else if (currentLine.startsWith("###### ")) {
                parsedViews.add(createHeadingView(currentLine.substring(7), 6));
            }
            else if (currentLine.matches("^\\s*[-*+]\\s.*")) {
                parsedViews.add(createUnorderedListItemView(currentLine));
            }
            else if (currentLine.matches("^\\s*\\d+\\.\\s.*")) {
                parsedViews.add(createOrderedListItemView(currentLine));
            }
            else if (currentLine.startsWith("![")) {
                parsedViews.add(createImageElementView(currentLine));
            }
            else {
                parsedViews.add(createBasicTextView(currentLine));
            }
        }

        if (isInsideTable && !tableRowsCollection.isEmpty()) {
            parsedViews.add(createTableView(tableRowsCollection));
        }

        if (isInsideCodeBlock && codeBlockContent.length() > 0) {
            parsedViews.add(createCodeBlockView(codeBlockContent.toString()));
        }

        return parsedViews;
    }

    /**
     * Отменяет все активные задачи загрузки изображений
     */
    public void cancelAllPendingTasks() {
        for (ImageLoadingTask task : activeImageLoadingTasks) {
            task.cancel(true);
        }
        activeImageLoadingTasks.clear();
    }

    /**
     * Очищает кэш изображений
     */
    public void clearImageCache() {
        imageCache.evictAll();
    }

    private TextView createBasicTextView(String textContent) {
        TextView textViewElement = new TextView(applicationContext);
        SpannableStringBuilder formattedText = new SpannableStringBuilder(textContent);

        applyTextFormattingBetweenDelimiters(formattedText, "**", new StyleSpan(Typeface.BOLD));
        applyTextFormattingBetweenDelimiters(formattedText, "*", new StyleSpan(Typeface.ITALIC));
        applyTextFormattingBetweenDelimiters(formattedText, "~~", new StrikethroughSpan());
        processHyperlinksInText(formattedText);

        textViewElement.setText(formattedText);
        textViewElement.setMovementMethod(LinkMovementMethod.getInstance());
        textViewElement.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        textViewElement.setPadding(0, convertDpToPixels(4), 0, convertDpToPixels(4));
        return textViewElement;
    }

    private void applyTextFormattingBetweenDelimiters(SpannableStringBuilder textBuilder,
                                                      String delimiter,
                                                      Object textStyleSpan) {
        int delimiterStartPosition;
        while ((delimiterStartPosition = textBuilder.toString().indexOf(delimiter)) != -1) {
            int delimiterEndPosition = textBuilder.toString().indexOf(
                    delimiter, delimiterStartPosition + delimiter.length());

            if (delimiterEndPosition != -1) {
                textBuilder.delete(delimiterEndPosition, delimiterEndPosition + delimiter.length());
                textBuilder.delete(delimiterStartPosition, delimiterStartPosition + delimiter.length());
                textBuilder.setSpan(textStyleSpan,
                        delimiterStartPosition,
                        delimiterEndPosition - delimiter.length(),
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private void processHyperlinksInText(SpannableStringBuilder textBuilder) {
        Pattern hyperlinkPattern = Pattern.compile("\\[(.*?)\\]\\((.*?)\\)");
        Matcher hyperlinkMatcher = hyperlinkPattern.matcher(textBuilder);

        while (hyperlinkMatcher.find()) {
            String linkText = hyperlinkMatcher.group(1);
            String linkUrl = hyperlinkMatcher.group(2);
            textBuilder.replace(hyperlinkMatcher.start(), hyperlinkMatcher.end(), linkText);
        }
    }

    private View createUnorderedListItemView(String listItemText) {
        TextView listItemView = new TextView(applicationContext);
        listItemView.setText("• " + listItemText.replaceFirst("^\\s*[-*+]\\s+", ""));
        listItemView.setPadding(convertDpToPixels(16), 0, 0, 0);
        listItemView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        return listItemView;
    }

    private View createOrderedListItemView(String listItemText) {
        TextView listItemView = new TextView(applicationContext);
        listItemView.setText(listItemText.replaceFirst("^(\\s*\\d+\\.)\\s+", "$1 "));
        listItemView.setPadding(convertDpToPixels(16), 0, 0, 0);
        listItemView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        return listItemView;
    }

    private View createCodeBlockView(String codeContent) {
        TextView codeBlockView = new TextView(applicationContext);
        codeBlockView.setText(codeContent);
        codeBlockView.setBackgroundColor(Color.parseColor("#f0f0f0"));
        codeBlockView.setTypeface(Typeface.MONOSPACE);
        codeBlockView.setPadding(
                convertDpToPixels(8),
                convertDpToPixels(8),
                convertDpToPixels(8),
                convertDpToPixels(8));
        codeBlockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return codeBlockView;
    }

    private TextView createHeadingView(String headingText, int headingLevel) {
        TextView headingView = new TextView(applicationContext);
        headingView.setText(headingText);
        float headingTextSize = 24 - (headingLevel * 2);
        headingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, headingTextSize);
        headingView.setTypeface(null, Typeface.BOLD);
        headingView.setPadding(0, convertDpToPixels(8), 0, convertDpToPixels(4));
        return headingView;
    }

    private String[] parseTableRow(String tableRowText) {
        String trimmedRow = tableRowText.replaceAll("^\\||\\|$", "");
        return trimmedRow.split("\\s*\\|\\s*", -1);
    }

    private boolean containsTableHeaderSeparator(String potentialSeparatorRow) {
        String[] tableCells = parseTableRow(potentialSeparatorRow);
        for (String cellContent : tableCells) {
            if (cellContent.trim().matches(":?-+:?")) {
                return true;
            }
        }
        return false;
    }

    private TableLayout createTableView(List<String[]> tableRows) {
        TableLayout tableLayout = new TableLayout(applicationContext);
        tableLayout.setLayoutParams(new TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT));
        tableLayout.setShrinkAllColumns(true);
        tableLayout.setStretchAllColumns(true);

        boolean[] isColumnRightAligned = new boolean[tableRows.get(0).length];
        if (tableRows.size() > 1 && containsTableHeaderSeparator(tableRows.get(1)[0])) {
            String[] alignmentMarkers = tableRows.get(1);
            for (int columnIndex = 0; columnIndex < alignmentMarkers.length; columnIndex++) {
                String cellContent = alignmentMarkers[columnIndex].trim();
                if (cellContent.startsWith(":")) {
                    isColumnRightAligned[columnIndex] = cellContent.endsWith(":");
                }
            }
        }

        for (int rowIndex = 0; rowIndex < tableRows.size(); rowIndex++) {
            if (rowIndex == 1 && containsTableHeaderSeparator(tableRows.get(rowIndex)[0])) {
                continue;
            }

            String[] rowCells = tableRows.get(rowIndex);
            TableRow tableRow = new TableRow(applicationContext);

            for (int cellIndex = 0; cellIndex < rowCells.length; cellIndex++) {
                TextView cellTextView = new TextView(applicationContext);
                cellTextView.setText(rowCells[cellIndex].trim());
                cellTextView.setPadding(
                        convertDpToPixels(8),
                        convertDpToPixels(4),
                        convertDpToPixels(8),
                        convertDpToPixels(4));
                cellTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

                int textAlignment = Gravity.START;
                if (isColumnRightAligned.length > cellIndex && isColumnRightAligned[cellIndex]) {
                    textAlignment = Gravity.END;
                }
                cellTextView.setGravity(textAlignment);

                if (rowIndex == 0) {
                    cellTextView.setTypeface(null, Typeface.BOLD);
                }

                tableRow.addView(cellTextView);
            }
            tableLayout.addView(tableRow);
        }

        tableLayout.addView(createTableDividerView());
        return tableLayout;
    }

    private View createTableDividerView() {
        View dividerView = new View(applicationContext);
        dividerView.setLayoutParams(new TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                convertDpToPixels(1)));
        dividerView.setBackgroundColor(Color.LTGRAY);
        return dividerView;
    }

    private View createImageElementView(String imageMarkdownSyntax) {
        try {
            Pattern imagePattern = Pattern.compile("!\\[(.*?)\\]\\((.*?)\\)");
            Matcher imageMatcher = imagePattern.matcher(imageMarkdownSyntax);

            if (!imageMatcher.find()) {
                return createErrorView("Invalid image syntax");
            }

            String imageDescription = imageMatcher.group(1);
            String imageSourceUrl = imageMatcher.group(2)
                    .replace(" ", "%20")
                    .replace("?", "%3F")
                    .replace("=", "%3D")
                    .replace("&", "%26");

            if (imageSourceUrl.contains("shields.io")) {
                imageSourceUrl += "?style=for-the-badge&logoWidth=40";
            }

            if (!isSupportedImageFormat(imageSourceUrl)) {
                return createErrorView("Unsupported image format");
            }

            ImageView imageViewElement = new ImageView(applicationContext);
            imageViewElement.setContentDescription(imageDescription);
            imageViewElement.setAdjustViewBounds(true);
            imageViewElement.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageViewElement.setPadding(0, convertDpToPixels(8), 0, convertDpToPixels(8));
            imageViewElement.setImageResource(R.drawable.ic_image_placeholder);

            ImageLoadingTask imageLoadingTask = new ImageLoadingTask(imageSourceUrl, imageViewElement);
            activeImageLoadingTasks.add(imageLoadingTask);
            imageLoadingTask.execute();

            return imageViewElement;
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Image element creation failed", exception);
            return createErrorView("Image loading failed");
        }
    }

    private boolean isSupportedImageFormat(String imageUrl) {
        return imageUrl.matches("(?i).*\\.(png|jpg|jpeg|gif|webp|svg)(\\?.*)?$");
    }

    private TextView createErrorView(String errorMessage) {
        TextView errorTextView = new TextView(applicationContext);
        errorTextView.setText("[Error: " + errorMessage + "]");
        errorTextView.setTextColor(Color.RED);
        return errorTextView;
    }

    private int convertDpToPixels(int dpValue) {
        return (int) (dpValue * applicationContext.getResources().getDisplayMetrics().density);
    }

    private class ImageLoadingTask extends AsyncTask<Void, Void, Bitmap> {
        private final String imageSourceUrl;
        private final WeakReference<ImageView> targetImageViewReference;

        ImageLoadingTask(String imageUrl, ImageView targetImageView) {
            this.imageSourceUrl = imageUrl;
            this.targetImageViewReference = new WeakReference<>(targetImageView);
        }

        @Override
        protected Bitmap doInBackground(Void... voids) {
            if (isCancelled()) return null;

            Bitmap cachedImage = imageCache.get(imageSourceUrl);
            if (cachedImage != null && !cachedImage.isRecycled()) {
                return cachedImage;
            }

            HttpURLConnection imageConnection = null;
            try {
                URL imageUrl = new URL(imageSourceUrl);
                if (imageSourceUrl.startsWith("https")) {
                    HttpsURLConnection secureConnection = (HttpsURLConnection) imageUrl.openConnection();
                    secureConnection.setHostnameVerifier((hostname, session) -> true);
                    imageConnection = secureConnection;
                } else {
                    imageConnection = (HttpURLConnection) imageUrl.openConnection();
                }

                configureConnectionSettings(imageConnection);

                int responseStatusCode = imageConnection.getResponseCode();
                if (responseStatusCode != HttpURLConnection.HTTP_OK) {
                    Log.w(LOG_TAG, "HTTP " + responseStatusCode + " for " + imageSourceUrl);
                    return null;
                }

                String responseContentType = imageConnection.getContentType();
                if (responseContentType == null || !responseContentType.startsWith("image/")) {
                    Log.w(LOG_TAG, "Invalid content type: " + responseContentType);
                    return null;
                }

                return decodeAndCacheImageStream(imageConnection);
            } catch (Exception exception) {
                if (!isCancelled()) {
                    Log.e(LOG_TAG, "Image loading error: " + imageSourceUrl, exception);
                }
                return null;
            } finally {
                if (imageConnection != null) {
                    imageConnection.disconnect();
                }
            }
        }

        private void configureConnectionSettings(HttpURLConnection connection) throws IOException {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
        }

        private Bitmap decodeAndCacheImageStream(HttpURLConnection connection) throws IOException {
            try (InputStream inputStream = connection.getInputStream()) {
                BitmapFactory.Options decodingOptions = new BitmapFactory.Options();
                decodingOptions.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(inputStream, null, decodingOptions);

                decodingOptions.inSampleSize = calculateOptimalScalingFactor(
                        decodingOptions,
                        applicationContext.getResources().getDisplayMetrics().widthPixels,
                        applicationContext.getResources().getDisplayMetrics().widthPixels);

                decodingOptions.inJustDecodeBounds = false;
                decodingOptions.inPreferredConfig = Bitmap.Config.RGB_565;

                connection.disconnect();
                connection = (HttpURLConnection) new URL(imageSourceUrl).openConnection();
                configureConnectionSettings(connection);

                try (InputStream newInputStream = connection.getInputStream()) {
                    Bitmap decodedBitmap = BitmapFactory.decodeStream(
                            newInputStream, null, decodingOptions);

                    if (decodedBitmap != null) {
                        imageCache.put(imageSourceUrl, decodedBitmap);
                    }
                    return decodedBitmap;
                }
            }
        }

        private int calculateOptimalScalingFactor(BitmapFactory.Options options,
                                                  int targetWidth,
                                                  int targetHeight) {
            final int originalWidth = options.outWidth;
            final int originalHeight = options.outHeight;
            int scalingFactor = 1;

            if (originalWidth > targetWidth || originalHeight > targetHeight) {
                final int halfWidth = originalWidth / 2;
                final int halfHeight = originalHeight / 2;

                while ((halfWidth / scalingFactor) >= targetWidth
                        && (halfHeight / scalingFactor) >= targetHeight) {
                    scalingFactor *= 2;
                }
            }
            return scalingFactor;
        }

        @Override
        protected void onPostExecute(Bitmap resultBitmap) {
            activeImageLoadingTasks.remove(this);
            ImageView targetImageView = targetImageViewReference.get();
            if (targetImageView == null || isCancelled()) {
                return;
            }

            if (resultBitmap != null) {
                targetImageView.setImageBitmap(resultBitmap);
            } else {
                targetImageView.setImageResource(R.drawable.ic_broken_image);
            }
        }
    }
    public void cleanup() {
        for (ImageLoadingTask task : activeImageLoadingTasks) {
            task.cancel(true);
        }
        activeImageLoadingTasks.clear();

        imageCache.evictAll();

        baseDocumentUrl = null;
    }
}
