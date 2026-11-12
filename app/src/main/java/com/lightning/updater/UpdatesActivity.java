/*
 * Copyright (C) 2017-2023 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lightning.updater;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.icu.text.DateFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemProperties;
import android.text.format.Formatter;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONException;
import com.lightning.updater.controller.UpdaterController;
import com.lightning.updater.controller.UpdaterService;
import com.lightning.updater.download.DownloadClient;
import com.lightning.updater.misc.BuildInfo;
import com.lightning.updater.misc.BuildInfoUtils;
import com.lightning.updater.misc.Constants;
import com.lightning.updater.misc.StringGenerator;
import com.lightning.updater.misc.Utils;
import com.lightning.updater.model.Update;
import com.lightning.updater.model.UpdateInfo;
import com.lightning.updater.model.UpdateStatus;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class UpdatesActivity extends UpdatesListActivity implements UpdateImporter.Callbacks {

    private static final String TAG = "UpdatesActivity";
    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private UpdatesListAdapter mAdapter;

    private boolean mIsTV;

    private UpdateInfo mToBeExported = null;

    private final ActivityResultLauncher<Intent> mExportUpdate = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent intent = result.getData();
                    if (intent != null) {
                        Uri uri = intent.getData();
                        exportUpdate(uri);
                    }
                }
            });

    private UpdateImporter mUpdateImporter;
    private AlertDialog importDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_updates);

        mUpdateImporter = new UpdateImporter(this, this);

        UiModeManager uiModeManager = getSystemService(UiModeManager.class);
        mIsTV = uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        mAdapter = new UpdatesListAdapter(this);
        recyclerView.setAdapter(mAdapter);
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setNestedScrollingEnabled(false);
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    handleDownloadStatusChange(downloadId);
                    mAdapter.notifyItemChanged(downloadId);
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction())) {
                    if (update != null) {
                        mAdapter.notifyItemChanged(downloadId);
                        updateStatusProgress(update);
                    }
                } else if (UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    if (update != null) {
                        mAdapter.notifyItemChanged(downloadId);
                    }
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    mAdapter.removeItem(downloadId);
                    List<UpdateInfo> sortedUpdates =
                            mUpdaterService.getUpdaterController().getUpdates();
                    setUpdatesListVisibility(sortedUpdates.isEmpty());
                }
            }
        };

        if (!mIsTV) {
            Toolbar toolbar = findViewById(R.id.toolbar);
            setSupportActionBar(toolbar);
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setDisplayShowTitleEnabled(false);
                actionBar.setDisplayHomeAsUpEnabled(true);
            }
        } else {
            findViewById(R.id.refresh).setOnClickListener(v -> downloadUpdatesList(true));
            findViewById(R.id.preferences).setOnClickListener(v -> showPreferencesDialog());
            TextView headerTitle = findViewById(R.id.header_title);
            headerTitle.setText(getString(R.string.header_title_text,
                    BuildInfoUtils.getBuildVersion()));
        }

        updateLastCheckedString();
        bindBuildInfo();

        MaterialButton btnCheck = findViewById(R.id.btn_check);
        if (btnCheck != null) {
            btnCheck.setOnClickListener(view -> {
                btnCheck.performHapticFeedback(
                        HapticFeedbackConstants.VIRTUAL_KEY,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                );
                downloadUpdatesList(true);
            });
        }
        MaterialButton btnLocalInstall = findViewById(R.id.btn_local_install);
        if (btnLocalInstall != null) {
            btnLocalInstall.setOnClickListener(view -> {
                btnLocalInstall.performHapticFeedback(
                        HapticFeedbackConstants.VIRTUAL_KEY,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                );
                mUpdateImporter.openImportPicker();
            });
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, UpdaterService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    protected void onPause() {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
            mUpdateImporter.stopImport();
        }

        super.onPause();
    }

    @Override
    public void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_preferences) {
            showPreferencesDialog();
            return true;
        } else if (itemId == R.id.menu_show_changelog) {
            Intent openUrl = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(Utils.getChangelogURL(this)));
            startActivity(openUrl);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (!mUpdateImporter.onResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onImportStarted() {
        if (importDialog != null && importDialog.isShowing()) {
            importDialog.dismiss();
        }

        importDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.local_update_import)
                .setView(R.layout.progress_dialog)
                .setCancelable(false)
                .create();

        importDialog.show();
    }

    @Override
    public void onImportCompleted(Update update) {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
        }

        if (update == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.local_update_import)
                    .setMessage(R.string.local_update_import_failure)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        mAdapter.notifyDataSetChanged();

        final Runnable deleteUpdate = () -> UpdaterController.getInstance(this)
                .deleteUpdate(update.getDownloadId());

        new AlertDialog.Builder(this)
                .setTitle(R.string.local_update_import)
                .setMessage(getString(R.string.local_update_import_success, update.getVersion()))
                .setPositiveButton(R.string.local_update_import_install, (dialog, which) -> {
                    mAdapter.addItem(update.getDownloadId());
                    // Update UI
                    getUpdatesList();
                    Utils.triggerUpdate(this, update.getDownloadId());
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> deleteUpdate.run())
                .setOnCancelListener((dialog) -> deleteUpdate.run())
                .show();
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            mAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            getUpdatesList();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAdapter.setUpdaterController(null);
            mUpdaterService = null;
            mAdapter.notifyDataSetChanged();
        }
    };

    private void loadUpdatesList(File jsonFile, boolean manualRefresh)
            throws IOException, JSONException {
        Log.d(TAG, "Adding remote updates");
        UpdaterController controller = mUpdaterService.getUpdaterController();
        boolean newUpdates = false;

        List<UpdateInfo> updates = Utils.parseJson(jsonFile, true);
        List<String> updatesOnline = new ArrayList<>();
        for (UpdateInfo update : updates) {
            newUpdates |= controller.addUpdate(update);
            updatesOnline.add(update.getDownloadId());
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true);

        if (manualRefresh) {
            showSnackbar(
                    newUpdates ? R.string.snack_updates_found : R.string.snack_no_updates_found,
                    Snackbar.LENGTH_SHORT);
        }

        List<UpdateInfo> sortedUpdates = controller.getUpdates();
        setUpdatesListVisibility(sortedUpdates.isEmpty());
        if (!sortedUpdates.isEmpty()) {
            sortedUpdates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));
            List<String> updateIds = new ArrayList<>();
            for (UpdateInfo update : sortedUpdates) {
                updateIds.add(update.getDownloadId());
            }
            mAdapter.setData(updateIds);
            mAdapter.notifyDataSetChanged();
        }
    }

    private void getUpdatesList() {
        File jsonFile = Utils.getCachedUpdateList(this);
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile, false);
                Log.d(TAG, "Cached list parsed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while parsing json list", e);
            }
        } else {
            downloadUpdatesList(false);
        }
    }

    private void processNewJson(File json, File jsonNew, boolean manualRefresh) {
        try {
            loadUpdatesList(jsonNew, manualRefresh);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            long millis = System.currentTimeMillis();
            preferences.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, millis).apply();
            updateLastCheckedString();
            if (json.exists() && Utils.isUpdateCheckEnabled(this) &&
                    Utils.checkForNewUpdates(json, jsonNew)) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(this);
            }
            // In case we set a one-shot check because of a previous failure
            UpdatesCheckReceiver.cancelUpdatesCheck(this);
            //noinspection ResultOfMethodCallIgnored
            jsonNew.renameTo(json);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read json", e);
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
        }
    }

    private void downloadUpdatesList(final boolean manualRefresh) {
        final File jsonFile = Utils.getCachedUpdateList(this);
        final File jsonFileTmp = new File(jsonFile.getAbsolutePath() + UUID.randomUUID());
        String url = Utils.getServerURL(this);
        Log.d(TAG, "Checking " + url);

        DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
            @Override
            public void onFailure(final boolean cancelled) {
                Log.e(TAG, "Could not download updates list");
                runOnUiThread(() -> {
                    if (!cancelled) {
                        showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
                    }
                    refreshAnimationStop();
                });
            }

            @Override
            public void onResponse(DownloadClient.Headers headers) {
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Log.d(TAG, "List downloaded");
                    processNewJson(jsonFile, jsonFileTmp, manualRefresh);
                    refreshAnimationStop();
                });
            }
        };

        final DownloadClient downloadClient;
        try {
            downloadClient = new DownloadClient.Builder()
                    .setUrl(url)
                    .setDestination(jsonFileTmp)
                    .setDownloadCallback(callback)
                    .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
            return;
        }

        refreshAnimationStart();
        downloadClient.start();
    }

    private void updateLastCheckedString() {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(this);
        long lastCheckMillis = preferences.getLong(Constants.PREF_LAST_UPDATE_CHECK, 0);

        if (mIsTV) {
            long lastCheck = lastCheckMillis / 1000;
            String lastCheckString = getString(R.string.header_last_updates_check,
                    StringGenerator.getDateLocalized(this, DateFormat.LONG, lastCheck),
                    StringGenerator.getTimeLocalized(this, lastCheck));
            TextView headerLastCheck = findViewById(R.id.header_last_check);
            headerLastCheck.setText(lastCheckString);

            TextView headerBuildVersion = findViewById(R.id.header_build_version);
            headerBuildVersion.setText(
                    getString(R.string.header_android_version, Build.VERSION.RELEASE));

            TextView headerBuildDate = findViewById(R.id.header_build_date);
            headerBuildDate.setText(getString(R.string.current_build_date,
                    StringGenerator.getDateLocalizedUTC(this,
                            DateFormat.LONG, BuildInfoUtils.getBuildDateTimestamp())));

            TextView headerBuildType = findViewById(R.id.header_build_type);
            String buildType = Utils.getBuildType();
            headerBuildType.setText(buildType == null || buildType.isEmpty()
                    ? getString(R.string.build_type_unknown)
                    : getString(R.string.current_build_type, buildType));
        } else {
            TextView lastChecked = findViewById(R.id.last_checked);
            if (lastChecked != null) {
                if (lastCheckMillis <= 0) {
                    // No check has ever happened: never show the Unix epoch
                    lastChecked.setText(R.string.updater_last_checked_never);
                } else {
                    long lastCheck = lastCheckMillis / 1000;
                    String when = StringGenerator.getDateLocalized(this,
                            DateFormat.LONG, lastCheck)
                            + " " + StringGenerator.getTimeLocalized(this, lastCheck);
                    lastChecked.setText(getString(R.string.updater_last_checked_with_time, when));
                }
            }
        }

        // Hide the support section when there is no OTA info at all
        String buildType = Utils.getBuildType();
        View supportLayout = findViewById(R.id.support_icons);
        if (supportLayout != null) {
            supportLayout.setVisibility(
                    buildType == null || buildType.isEmpty() ? View.GONE : View.VISIBLE);
        }

        TextView maintainerName = findViewById(R.id.maintainer_name);
        String maintainer = Utils.getMaintainer();
        if (maintainerName != null) {
            if (maintainer == null || maintainer.isEmpty()) {
                maintainerName.setVisibility(View.GONE);
            } else {
                maintainerName.setText(getString(R.string.maintainer_name, maintainer));
                maintainerName.setVisibility(View.VISIBLE);
            }
        }

        setupSupportLinks();
    }

    private void setupSupportLinks() {
        openSupportLink(R.id.support_forum, Utils.getForum());
        openSupportLink(R.id.support_telegram, Utils.getTelegram());
        openSupportLink(R.id.support_recovery, Utils.getRecovery());
        openSupportLink(R.id.support_paypal, Utils.getPaypal());
    }

    private void openSupportLink(int viewId, String url) {
        View link = findViewById(viewId);
        if (link == null) {
            return;
        }
        if (url == null || url.isEmpty()) {
            link.setVisibility(View.GONE);
        } else {
            link.setVisibility(View.VISIBLE);
            link.setOnClickListener(v -> {
                Intent intent = new Intent();
                intent.setAction(Intent.ACTION_VIEW);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setData(Uri.parse(url));
                startActivity(intent);
            });
        }
    }

    private void bindBuildInfo() {
        if (mIsTV) {
            return;
        }
        String buildId = BuildInfoUtils.getBuildVersion();
        BuildInfo info = BuildInfo.fromSystem();
        TextView romName = findViewById(R.id.rom_name);
        TextView romVersion = findViewById(R.id.rom_version);
        ChipGroup chips = findViewById(R.id.hero_chips);
        LinearLayout rows = findViewById(R.id.details_rows);
        TextView buildIdValue = findViewById(R.id.build_id_value);
        if (romName == null || romVersion == null || chips == null || rows == null
                || buildIdValue == null) {
            return;
        }

        chips.removeAllViews();
        rows.removeAllViews();
        buildIdValue.setText(buildId);

        romName.setText(info.rom);
        if (!info.version.isEmpty() && !info.codename.isEmpty()) {
            romVersion.setText(getString(R.string.updater_version_line,
                    info.version, info.codename));
        } else if (!info.version.isEmpty()) {
            romVersion.setText(getString(R.string.updater_version_simple, info.version));
        } else if (info.android != null && !info.android.isEmpty()) {
            romVersion.setText(getString(R.string.header_android_version, info.android));
        } else {
            romVersion.setText("");
        }

        if (info.android != null && !info.android.isEmpty()) {
            addChip(chips, getString(R.string.updater_chip_android, info.androidShort()));
        }
        if (info.flavor != null && !info.flavor.isEmpty()) {
            addChip(chips, info.flavor);
        }
        String typeDisplay = info.typeDisplay();
        if (!typeDisplay.isEmpty()) {
            addChip(chips, typeDisplay);
        }

        String buildDate = info.buildDateDisplay(
                getResources().getConfiguration().getLocales().get(0));
        if (!buildDate.isEmpty()) {
            addRow(rows, R.string.updater_label_build_date, buildDate);
        }
        if (info.device != null && !info.device.isEmpty()) {
            addRow(rows, R.string.updater_label_device, info.device);
        }
        if (info.android != null && !info.android.isEmpty()) {
            addRow(rows, R.string.updater_label_android, info.android);
        }
        if (info.flavor != null && !info.flavor.isEmpty()) {
            addRow(rows, R.string.updater_label_flavor, info.flavor);
        }
        addRow(rows, R.string.updater_label_channel,
                info.hasOtaInfo() ? typeDisplay
                        : getString(R.string.updater_channel_unofficial));

        View copyButton = findViewById(R.id.build_id_copy);
        if (copyButton != null) {
            copyButton.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(
                        Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText(
                        getString(R.string.updater_label_build_id), buildId));
                showSnackbar(R.string.updater_build_id_copied, Snackbar.LENGTH_SHORT);
            });
        }
    }

    private void addChip(ChipGroup group, String text) {
        Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_updater_chip, group, false);
        chip.setText(text);
        group.addView(chip);
    }

    private void addRow(LinearLayout container, int labelRes, String value) {
        View row = getLayoutInflater().inflate(R.layout.item_updater_detail_row, container, false);
        ((TextView) row.findViewById(R.id.detail_label)).setText(labelRes);
        ((TextView) row.findViewById(R.id.detail_value)).setText(value);
        container.addView(row);
    }

    private void setUpdatesListVisibility(boolean empty) {
        View recyclerView = findViewById(R.id.recycler_view);
        if (recyclerView != null) {
            recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
        if (mIsTV) {
            View emptyView = findViewById(R.id.no_new_updates_view);
            if (emptyView != null) {
                emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
            }
        } else {
            renderStatusCard();
        }
    }

    private void renderStatusCard() {
        if (mIsTV) {
            return;
        }
        FrameLayout badge = findViewById(R.id.status_badge);
        ImageView icon = findViewById(R.id.status_icon);
        TextView title = findViewById(R.id.status_title);
        TextView message = findViewById(R.id.status_message);
        if (badge == null || icon == null || title == null || message == null) {
            return;
        }

        boolean hasUpdates = mAdapter != null && mAdapter.getItemCount() > 0;
        if (hasUpdates) {
            badge.setBackgroundResource(R.drawable.bg_updater_badge);
            icon.setImageResource(R.drawable.ic_updater_install_primary);
            title.setText(R.string.updater_update_available);

            UpdateInfo newest = null;
            if (mUpdaterService != null) {
                for (UpdateInfo update : mUpdaterService.getUpdaterController().getUpdates()) {
                    if (newest == null || update.getTimestamp() > newest.getTimestamp()) {
                        newest = update;
                    }
                }
            }
            if (newest != null) {
                message.setText(getString(R.string.updater_update_available_info,
                        newest.getVersion(),
                        Formatter.formatShortFileSize(this, newest.getFileSize())));
                message.setVisibility(View.VISIBLE);
            } else {
                message.setVisibility(View.GONE);
            }
        } else {
            badge.setBackgroundResource(R.drawable.bg_updater_badge_done);
            icon.setImageResource(R.drawable.ic_updater_check);
            title.setText(R.string.updater_up_to_date);
            message.setText(R.string.updater_no_updates);
            message.setVisibility(View.VISIBLE);
        }
    }

    private void updateStatusProgress(UpdateInfo update) {
        if (mIsTV) {
            return;
        }
        View progressBlock = findViewById(R.id.progress_block);
        LinearProgressIndicator progress = findViewById(R.id.download_progress);
        TextView label = findViewById(R.id.progress_label);
        if (progressBlock == null || progress == null || label == null) {
            return;
        }
        UpdateStatus status = update.getStatus();
        if (status == UpdateStatus.DOWNLOADING || status == UpdateStatus.PAUSED) {
            progressBlock.setVisibility(View.VISIBLE);
            int percent = update.getProgress();
            progress.setProgressCompat(percent, true);
            label.setText(getString(status == UpdateStatus.DOWNLOADING
                    ? R.string.updater_downloading_percent
                    : R.string.updater_paused_percent, percent));
        } else {
            progressBlock.setVisibility(View.GONE);
        }
    }

    private void handleDownloadStatusChange(String downloadId) {
        if (Update.LOCAL_ID.equals(downloadId)) {
            return;
        }

        UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
        if (update == null) {
            return;
        }
        switch (update.getStatus()) {
            case PAUSED_ERROR:
                showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG);
                hideProgressBars();
                break;
            case VERIFICATION_FAILED:
                showSnackbar(R.string.snack_download_verification_failed, Snackbar.LENGTH_LONG);
                hideProgressBars();
                break;
            case VERIFIED:
                showSnackbar(R.string.snack_download_verified, Snackbar.LENGTH_LONG);
                hideProgressBars();
                break;
            default:
                break;
        }
    }

    @Override
    public void exportUpdate(UpdateInfo update) {
        mToBeExported = update;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, update.getName());

        mExportUpdate.launch(intent);
    }

    private void exportUpdate(Uri uri) {
        Intent intent = new Intent(this, ExportUpdateService.class);
        intent.setAction(ExportUpdateService.ACTION_START_EXPORTING);
        intent.putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, mToBeExported.getFile());
        intent.putExtra(ExportUpdateService.EXTRA_DEST_URI, uri);
        startService(intent);
    }

    @Override
    public void showSnackbar(int stringId, int duration) {
        Snackbar snackbar = Snackbar.make(findViewById(R.id.main_container), stringId, duration);
        snackbar.setAnchorView(mIsTV ? R.id.refresh : R.id.action_bar);
        View snackbarView = snackbar.getView();
        snackbarView.setElevation(6f);
        snackbarView.setBackground(getDrawable(R.drawable.snackbar_background));
        TextView textView = snackbarView.findViewById(com.google.android.material.R.id.snackbar_text);
        textView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) snackbarView.getLayoutParams();
        params.bottomMargin += getResources().getDimensionPixelSize(R.dimen.snackbar_spacing);
        params.leftMargin += getResources().getDimensionPixelSize(R.dimen.snackbar_horizontal_margin);
        params.rightMargin += getResources().getDimensionPixelSize(R.dimen.snackbar_horizontal_margin);
        snackbarView.setLayoutParams(params);
        snackbar.show();
    }

    private void refreshAnimationStart() {
        if (!mIsTV) {
            View spinner = findViewById(R.id.status_spinner);
            View statusIcon = findViewById(R.id.status_icon);
            FrameLayout statusBadge = findViewById(R.id.status_badge);
            TextView statusTitle = findViewById(R.id.status_title);
            MaterialButton btnCheck = findViewById(R.id.btn_check);
            MaterialButton btnLocalInstall = findViewById(R.id.btn_local_install);
            if (spinner != null) {
                spinner.setVisibility(View.VISIBLE);
            }
            if (statusIcon != null) {
                statusIcon.setVisibility(View.INVISIBLE);
            }
            if (statusBadge != null) {
                statusBadge.setBackgroundResource(R.drawable.bg_updater_badge_checking);
            }
            if (statusTitle != null) {
                statusTitle.setText(R.string.updater_checking);
            }
            if (btnCheck != null) {
                btnCheck.setEnabled(false);
            }
            if (btnLocalInstall != null) {
                btnLocalInstall.setEnabled(false);
            }
        } else {
            findViewById(R.id.recycler_view).setVisibility(View.GONE);
            findViewById(R.id.no_new_updates_view).setVisibility(View.GONE);
            findViewById(R.id.refresh_progress).setVisibility(View.VISIBLE);
        }
    }

    private void refreshAnimationStop() {
        if (!mIsTV) {
            View spinner = findViewById(R.id.status_spinner);
            View statusIcon = findViewById(R.id.status_icon);
            MaterialButton btnCheck = findViewById(R.id.btn_check);
            MaterialButton btnLocalInstall = findViewById(R.id.btn_local_install);
            if (spinner != null) {
                spinner.setVisibility(View.GONE);
            }
            if (statusIcon != null) {
                statusIcon.setVisibility(View.VISIBLE);
            }
            if (btnCheck != null) {
                btnCheck.setEnabled(true);
            }
            if (btnLocalInstall != null) {
                btnLocalInstall.setEnabled(true);
            }
            renderStatusCard();
        } else {
            findViewById(R.id.refresh_progress).setVisibility(View.GONE);
            if (mAdapter.getItemCount() > 0) {
                findViewById(R.id.recycler_view).setVisibility(View.VISIBLE);
            } else {
                findViewById(R.id.no_new_updates_view).setVisibility(View.VISIBLE);
            }
        }
    }

    private void hideProgressBars() {
        View progressBlock = findViewById(R.id.progress_block);
        if (progressBlock != null) {
            progressBlock.setVisibility(View.GONE);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showPreferencesDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.preferences_dialog, null);
        Spinner autoCheckInterval = view.findViewById(R.id.preferences_auto_updates_check_interval);
        MaterialSwitch autoDelete = view.findViewById(R.id.preferences_auto_delete_updates);
        MaterialSwitch meteredNetworkWarning = view.findViewById(
                R.id.preferences_metered_network_warning);
        MaterialSwitch abPerfMode = view.findViewById(R.id.preferences_ab_perf_mode);
        MaterialSwitch updateRecovery = view.findViewById(R.id.preferences_update_recovery);

        if (!Utils.isABDevice()) {
            abPerfMode.setVisibility(View.GONE);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        autoCheckInterval.setSelection(Utils.getUpdateCheckSetting(this));
        autoDelete.setChecked(prefs.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false));
        meteredNetworkWarning.setChecked(prefs.getBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                prefs.getBoolean(Constants.PREF_MOBILE_DATA_WARNING, true)));
        abPerfMode.setChecked(prefs.getBoolean(Constants.PREF_AB_PERF_MODE, false));

        if (getResources().getBoolean(R.bool.config_hideRecoveryUpdate)) {
            // Hide the update feature if explicitly requested.
            // Might be the case of A-only devices using prebuilt vendor images.
            updateRecovery.setVisibility(View.GONE);
        } else if (Utils.isRecoveryUpdateExecPresent()) {
            updateRecovery.setChecked(
                    SystemProperties.getBoolean(Constants.UPDATE_RECOVERY_PROPERTY, false));
        } else {
            // There is no recovery updater script in the device, so the feature is considered
            // forcefully enabled, just to avoid users to be confused and complain that
            // recovery gets overwritten. That's the case of A/B and recovery-in-boot devices.
            updateRecovery.setChecked(true);
            updateRecovery.setOnTouchListener(new View.OnTouchListener() {
                private Toast forcedUpdateToast = null;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (forcedUpdateToast != null) {
                        forcedUpdateToast.cancel();
                    }
                    forcedUpdateToast = Toast.makeText(getApplicationContext(),
                            getString(R.string.toast_forced_update_recovery), Toast.LENGTH_SHORT);
                    forcedUpdateToast.show();
                    return true;
                }
            });
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_preferences)
                .setView(view)
                .setOnDismissListener(dialogInterface -> {
                    prefs.edit()
                            .putInt(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
                                    autoCheckInterval.getSelectedItemPosition())
                            .putBoolean(Constants.PREF_AUTO_DELETE_UPDATES, autoDelete.isChecked())
                            .putBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                                    meteredNetworkWarning.isChecked())
                            .putBoolean(Constants.PREF_AB_PERF_MODE, abPerfMode.isChecked())
                            .apply();

                    if (Utils.isUpdateCheckEnabled(this)) {
                        UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(this);
                    } else {
                        UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(this);
                        UpdatesCheckReceiver.cancelUpdatesCheck(this);
                    }

                    if (Utils.isABDevice()) {
                        boolean enableABPerfMode = abPerfMode.isChecked();
                        mUpdaterService.getUpdaterController().setPerformanceMode(enableABPerfMode);
                    }
                    if (Utils.isRecoveryUpdateExecPresent()) {
                        boolean enableRecoveryUpdate = updateRecovery.isChecked();
                        SystemProperties.set(Constants.UPDATE_RECOVERY_PROPERTY,
                                String.valueOf(enableRecoveryUpdate));
                    }
                })
                .show();
    }
}
