package gg.vape.sync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import gg.vape.Vape;
import gg.vape.account.AccountInfo;
import gg.vape.api.ApiHttpClient;
import gg.vape.api.ApiHttpStatusException;
import gg.vape.api.ApiResponse;
import gg.vape.api.ApiServices;
import gg.vape.api.UserDataResponse;
import gg.vape.config.ConfigJsonUtils;
import gg.vape.config.LocalConfigStore;
import gg.vape.config.Profile;
import gg.vape.config.SettingsDataType;
import gg.vape.manager.client.OnlineConnectionManager;
import gg.vape.notification.SettingsSyncStatusNotification;
import gg.vape.runtime.NativeBridge;
import gg.vape.utils.Base64Util;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class SyncThread {
    /** 单次远端保存等待上限：join() 无超时会让调用线程（保存线程/关闭钩子）永久挂住。 */
    private static final long SAVE_JOIN_TIMEOUT_SECONDS = 30L;
    private SyncStoreRequestWorker storeRequestWorker;
    private final SyncDebounceWorker debounceWorker;
    private final AtomicBoolean pendingSave = new AtomicBoolean();
    /** 脏代次：保存期间 markDirty 会推进它，保存结束不得把新的脏状态清掉。 */
    private final AtomicLong dirtyGeneration = new AtomicLong();
    private final Vape vape;
    /** 显式运行标志；{@link Vape#isEnabled()} 的 enabled 字段全源码无赋值，不能当生命周期用。 */
    private final AtomicBoolean running = new AtomicBoolean(true);
    /** 工作线程句柄，供 {@link #stop()} 显式打断。 */
    volatile Thread debounceThread;
    volatile Thread storeThread;
    private long lastSaveTime;
    private boolean onlineSettingsApplied;

    public SyncThread(Vape vape) {
        this.vape = vape;
        this.debounceWorker = new SyncDebounceWorker(this.running::get);
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::saveLocalSnapshot, "Vape local config save"));
    }

    /** @return 同步线程是否仍在运行 */
    public boolean isRunning() {
        return this.running.get();
    }

    /**
     * 停止同步线程：先清运行标志，再显式打断两个工作线程。
     *
     * <p>只清标志不够——两个循环各自 sleep 1s/100ms，不打断最多要等一整秒才退出，而自销毁路径
     * 需要确定性的结束。
     */
    public void stop() {
        this.running.set(false);
        Thread debounce = this.debounceThread;
        if (debounce != null) {
            debounce.interrupt();
        }
        Thread store = this.storeThread;
        if (store != null) {
            store.interrupt();
        }
    }

    public void saveSettings() {
        long generation = this.dirtyGeneration.get();
        try {
            SettingsSyncStatusNotification notification = new SettingsSyncStatusNotification();
            if (!this.vape.getPublicProfileSettings().autoSave.getEffectiveValue()) {
                this.vape.getNotificationManager().enqueue(notification, true);
            }

            this.syncOnlineSettings();
            this.prepareActiveProfileForSave();

            JsonObject settingsPayload = this.buildSettingsPayload(true);
            JsonObject profilesPayload = this.vape.getProfilesManager().toJson(true);
            for (Profile profile : this.vape.getProfilesManager().getProfiles()) {
                profile.setSaveQueued(true);
            }

            boolean localSaveOk = LocalConfigStore.saveSettings(settingsPayload)
                    | LocalConfigStore.saveProfiles(
                            this.buildLocalProfilesPayload());

            ApiResponse<Boolean> settingsResponse = ApiServices.getInstance().getUserDataApi().saveUserData(settingsPayload)
                    .exceptionally(error -> handleSettingsSaveFailure(notification, error))
                    .get(SAVE_JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            this.updateSettingsSaveStatus(notification, settingsResponse);

            ApiResponse<RemoteProfileDataMap> profilesResponse = ApiServices.getInstance().getUserDataApi().saveProfileData(profilesPayload)
                    .exceptionally(error -> handleProfilesSaveFailure(notification, error))
                    .get(SAVE_JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            this.updateProfilesSaveStatus(notification, profilesResponse);
            this.applySavedProfileIds(profilesResponse);

            notification.complete();
            if (notification.hasSaveError()
                    && this.vape.getPublicProfileSettings().autoSave.getEffectiveValue()
                    && !localSaveOk) {
                this.vape.getNotificationManager().show(notification);
            }
        }
        catch (InterruptedException interrupted) {
            // stop()/panic 打断的是等待中的保存：恢复中断位并立刻退出，
            // 不能落进通用 Exception 被当成一次普通保存失败继续跑。
            Thread.currentThread().interrupt();
            return;
        }
        catch (Exception exception) {
            Vape.logThrowable(exception);
        }
        finally {
            // 保存期间 markDirty 必须保留：只有代次没变才能清 pending。
            if (this.dirtyGeneration.get() == generation) {
                this.pendingSave.set(false);
            }
        }
    }

    /**
     * Writes the current settings/profiles to the local config file without
     * touching the online API. Used by the JVM shutdown hook so changes made
     * with the GUI closed are still persisted.
     */
    public void saveLocalSnapshot() {
        try {
            this.prepareActiveProfileForSave();
            LocalConfigStore.saveSettings(this.buildSettingsPayload(true));
            LocalConfigStore.saveProfiles(
                    this.buildLocalProfilesPayload());
        }
        catch (Throwable ignored) {
        }
    }

    private JsonObject buildLocalProfilesPayload() {
        JsonObject localProfiles = new JsonObject();
        for (Profile profile : this.vape.getProfilesManager().getProfiles()) {
            localProfiles.add(profile.getLocalId().toString(),
                    profile.toJson(false));
        }
        return localProfiles;
    }

    private JsonObject normalizeLocalProfilesPayload(JsonObject payload) {
        if (!payload.has("updatedProfiles")) {
            return payload;
        }
        com.google.gson.JsonArray updated =
                payload.getAsJsonArray("updatedProfiles");
        JsonObject normalized = new JsonObject();
        for (com.google.gson.JsonElement element : updated) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject profile = element.getAsJsonObject();
            String uuid = ConfigJsonUtils.getString(profile, "uuid");
            if (uuid == null) {
                continue;
            }
            normalized.add(uuid, profile);
        }
        return normalized;
    }

    private void prepareActiveProfileForSave() {
        try {
            Profile activeProfile = this.vape.getProfilesManager().getActiveProfile();
            if (activeProfile != null) {
                activeProfile.captureCurrentState();
            }
        }
        catch (Throwable throwable) {
            Vape.logThrowable(throwable);
        }
    }

    private void updateSettingsSaveStatus(SettingsSyncStatusNotification notification, ApiResponse<Boolean> response) {
        if (response == null) {
            if (notification.getSettingsSaveStatus() == 1) {
                notification.setSettingsSaveStatus(5);
            }
            return;
        }
        if (response.isSuccessful()) {
            notification.setSettingsSaveStatus(1);
        } else if (isRateLimited(response)) {
            notification.setSettingsSaveStatus(3);
        } else {
            notification.setSettingsSaveStatus(4);
        }
    }

    private void updateProfilesSaveStatus(SettingsSyncStatusNotification notification, ApiResponse<RemoteProfileDataMap> response) {
        if (response == null) {
            if (notification.getProfilesSaveStatus() == 1) {
                notification.setProfilesSaveStatus(5);
            }
            return;
        }
        if (response.isSuccessful()) {
            notification.setProfilesSaveStatus(1);
            for (Profile profile : this.vape.getProfilesManager().getProfiles()) {
                profile.setDirty(false);
            }
        } else if (isRateLimited(response)) {
            notification.setProfilesSaveStatus(3);
        } else {
            notification.setProfilesSaveStatus(4);
            notification.setApiErrorDetail(response.getError());
        }
    }

    private void applySavedProfileIds(ApiResponse<RemoteProfileDataMap> response) {
        if (response == null || !response.isSuccessful()) {
            return;
        }
        RemoteProfileDataMap responseData = response.getData();
        assert responseData != null;
        if (responseData == null) {
            throw new IllegalStateException("Successful profile save response did not include profile data");
        }
        for (Profile profile : this.vape.getProfilesManager().getProfiles()) {
            RemoteProfileData savedProfile = responseData.getProfiles().values().stream()
                    .filter(candidate -> matchesProfileName(profile, candidate))
                    .findFirst()
                    .orElse(null);
            if (savedProfile != null && !savedProfile.getProfileId().equals(profile.getOnlineId())) {
                profile.setOnlineId(savedProfile.getProfileId());
            }
        }
    }

    private static boolean matchesProfileName(Profile profile, RemoteProfileData remoteProfile) {
        String profileName = profile.getName();
        return remoteProfile.getName().equals(profileName)
                || remoteProfile.getName().equals("b64:" + Base64Util.encodeUtf8Base64(profileName));
    }

    private static boolean isRateLimited(ApiResponse<?> response) {
        String error = response.getError();
        return error != null && (error.contains("Slow down") || error.contains("Rate limited"));
    }

    private static ApiResponse<Boolean> handleSettingsSaveFailure(SettingsSyncStatusNotification notification, Throwable error) {
        Throwable cause = rootCause(error);
        if (cause instanceof ApiHttpStatusException) {
            notification.setSettingsSaveStatus(((ApiHttpStatusException)cause).getStatusCode());
        } else {
            notification.setSettingsSaveStatus(2);
        }
        return null;
    }

    private static ApiResponse<RemoteProfileDataMap> handleProfilesSaveFailure(SettingsSyncStatusNotification notification, Throwable error) {
        Throwable cause = rootCause(error);
        if (cause instanceof ApiHttpStatusException) {
            notification.setProfilesSaveStatus(((ApiHttpStatusException)cause).getStatusCode());
        } else {
            notification.setProfilesSaveStatus(2);
            if (cause instanceof IOException) {
                notification.setIoExceptionClassName(cause.getClass().getName());
                notification.setIoExceptionMessage(cause.getMessage());
            }
        }
        return null;
    }

    private static Throwable rootCause(Throwable throwable) {
        while (throwable.getCause() != null) {
            throwable = throwable.getCause();
        }
        return throwable;
    }

    public void requestSave() {
        this.lastSaveTime = System.currentTimeMillis();
        if (this.storeRequestWorker == null) {
            if (!this.running.get()) {
                return;
            }
            this.storeRequestWorker = new SyncStoreRequestWorker(this.running::get);
            Thread thread = new Thread(this.storeRequestWorker, "Vape settings save worker");
            thread.setDaemon(true);
            this.storeThread = thread;
            thread.start();
        }
        this.storeRequestWorker.requestSave();
    }

    public boolean hasPendingSave() {
        return this.pendingSave.get();
    }

    public long getLastSaveTime() {
        return this.lastSaveTime;
    }

    public void loadConfig() {
        try {
            JsonObject localSettings = LocalConfigStore.loadSettings();
            if (localSettings != null) {
                JsonObject config = new JsonObject();
                if (localSettings.has("friends")) {
                    config.add("friends", localSettings.get("friends"));
                }
                if (localSettings.has("otherData")) {
                    config.add("otherData", localSettings.get("otherData"));
                }
                JsonObject localProfiles = LocalConfigStore.loadProfiles();
                if (localProfiles != null) {
                    config.add("profiles",
                            this.normalizeLocalProfilesPayload(localProfiles));
                }
                this.vape.loadConfigData(config, true);
                return;
            }
            AccountInfo accountInfo = this.vape.getAccountInfo();
            if (accountInfo != null && accountInfo.hasProfilesEnabled()) {
                this.loadRemoteConfig();
            } else {
                this.loadStandaloneConfig();
            }
        }
        catch (Throwable ignored) {
        }
    }

    private void loadRemoteConfig() {
        ApiResponse<UserDataResponse> response = ApiServices.getInstance().getUserDataApi().getUserData()
                .exceptionally(error -> null)
                .join();
        if (response == null || !response.isSuccessful()) {
            return;
        }
        UserDataResponse userData = response.getData();
        assert userData != null;
        if (userData == null) {
            return;
        }

        HashMap<UUID, JsonObject> profiles = new HashMap<UUID, JsonObject>();
        for (RemoteProfileData remoteProfile : userData.getProfiles().values()) {
            profiles.put(remoteProfile.getProfileId(), remoteProfile.toJson());
        }
        this.vape.getPublicProfileManager().replaceProfiles(userData.getPublicProfiles().values());

        JsonObject config = new JsonObject();
        if (userData.getFriends() != null) {
            config.add("friends", userData.getFriends());
        }
        config.add("profiles", ApiHttpClient.GSON.toJsonTree(profiles));
        if (userData.getOtherData() != null) {
            config.add("otherData", userData.getOtherData());
        }
        this.vape.loadConfigData(config, true);
    }

    private void loadStandaloneConfig() {
        String encodedSettings = NativeBridge.gp("all");
        String decodedSettings = encodedSettings == null
                ? ""
                : new String(Base64Util.decodeBase64(encodedSettings), StandardCharsets.UTF_8).trim();
        JsonReader reader = new JsonReader(new StringReader(decodedSettings));
        reader.setLenient(true);
        JsonObject config = new Gson().fromJson(reader, JsonObject.class);
        if (config == null) {
            return;
        }
        this.vape.loadConfigData(config, false);
        for (Profile profile : this.vape.getProfilesManager().getProfiles()) {
            profile.setDirty(true);
        }
    }

    public void clearPendingSave() {
        this.pendingSave.set(false);
    }

    public void markDirty() {
        this.pendingSave.set(true);
        this.dirtyGeneration.incrementAndGet();
        this.debounceWorker.markChanged();
    }

    public void start() {
        Thread thread = new Thread(this.debounceWorker, "Vape settings sync worker");
        thread.setDaemon(true);
        this.debounceThread = thread;
        thread.start();
    }

    private void syncOnlineSettings() {
        if (!OnlineConnectionManager.INSTANCE.getSettings().hasLoadFailed()) {
            try {
                ApiServices.getInstance().getSettingsApi().saveSettings(SettingsDataType.ONLINE, OnlineConnectionManager.INSTANCE.getSettings().getPayload());
            }
            catch (Exception ignored) {
            }
        }
        try {
            boolean[] onlineSettingsState = NativeBridge.gls();
            boolean shouldApplySettings = onlineSettingsState[0];
            boolean secondaryState = onlineSettingsState[1];
            if (!this.onlineSettingsApplied && shouldApplySettings) {
                OnlineConnectionManager.INSTANCE.getGlobalSettingsController().getCacheData().setPersistenceSuppressed(true);
                OnlineConnectionManager.INSTANCE.getGlobalSettingsController().getCacheData().setValue(secondaryState);
                OnlineConnectionManager.INSTANCE.getGlobalSettingsController().getCacheData().setPersistenceSuppressed(false);
                this.onlineSettingsApplied = true;
            }
            OnlineConnectionManager.INSTANCE.getGlobalSettingsController().save();
        }
        catch (Throwable ignored) {
        }
    }

    public JsonObject buildSettingsPayload(boolean splitProfiles) {
        JsonObject payload = new JsonObject();
        payload.add("friends", this.vape.getFriendManager().toJson());
        payload.add(splitProfiles ? "otherData" : "otherdata", this.vape.getSettingsManager().toJson());
        if (!splitProfiles) {
            payload.add("profiles", this.vape.getProfilesManager().toJson(false));
        }
        return payload;
    }
}
