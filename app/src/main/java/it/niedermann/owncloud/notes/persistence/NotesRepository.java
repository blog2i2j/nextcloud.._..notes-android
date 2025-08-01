/*
 * Nextcloud Notes - Android Client
 *
 * SPDX-FileCopyrightText: 2021-2024 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.owncloud.notes.persistence;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.O;
import static androidx.lifecycle.Transformations.distinctUntilChanged;
import static androidx.lifecycle.Transformations.map;
import static java.util.stream.Collectors.toMap;
import static it.niedermann.owncloud.notes.edit.EditNoteActivity.ACTION_SHORTCUT;
import static it.niedermann.owncloud.notes.shared.util.ApiVersionUtil.getPreferredApiVersion;
import static it.niedermann.owncloud.notes.shared.util.NoteUtil.generateNoteExcerpt;
import static it.niedermann.owncloud.notes.widget.notelist.NoteListWidget.updateNoteListWidgets;
import static it.niedermann.owncloud.notes.widget.singlenote.SingleNoteWidget.updateSingleNoteWidgets;

import android.accounts.NetworkErrorException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.ColorInt;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.preference.PreferenceManager;

import com.nextcloud.android.sso.AccountImporter;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException;
import com.nextcloud.android.sso.exceptions.NoCurrentAccountSelectedException;
import com.nextcloud.android.sso.helper.SingleAccountHelper;
import com.nextcloud.android.sso.model.SingleSignOnAccount;
import com.owncloud.android.lib.common.utils.Log_OC;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import it.niedermann.android.sharedpreferences.SharedPreferenceIntLiveData;
import it.niedermann.owncloud.notes.BuildConfig;
import it.niedermann.owncloud.notes.R;
import it.niedermann.owncloud.notes.edit.EditNoteActivity;
import it.niedermann.owncloud.notes.persistence.entity.Account;
import it.niedermann.owncloud.notes.persistence.entity.CategoryOptions;
import it.niedermann.owncloud.notes.persistence.entity.CategoryWithNotesCount;
import it.niedermann.owncloud.notes.persistence.entity.Note;
import it.niedermann.owncloud.notes.persistence.entity.NotesListWidgetData;
import it.niedermann.owncloud.notes.persistence.entity.ShareEntity;
import it.niedermann.owncloud.notes.persistence.entity.SingleNoteWidgetData;
import it.niedermann.owncloud.notes.shared.model.ApiVersion;
import it.niedermann.owncloud.notes.shared.model.Capabilities;
import it.niedermann.owncloud.notes.shared.model.CategorySortingMethod;
import it.niedermann.owncloud.notes.shared.model.DBStatus;
import it.niedermann.owncloud.notes.shared.model.ENavigationCategoryType;
import it.niedermann.owncloud.notes.shared.model.IResponseCallback;
import it.niedermann.owncloud.notes.shared.model.ISyncCallback;
import it.niedermann.owncloud.notes.shared.model.ImportStatus;
import it.niedermann.owncloud.notes.shared.model.NavigationCategory;
import it.niedermann.owncloud.notes.shared.model.NotesSettings;
import it.niedermann.owncloud.notes.shared.model.SyncResultStatus;
import it.niedermann.owncloud.notes.shared.util.ApiVersionUtil;
import it.niedermann.owncloud.notes.shared.util.ConnectionLiveData;
import it.niedermann.owncloud.notes.shared.util.NoteUtil;
import it.niedermann.owncloud.notes.shared.util.SSOUtil;
import retrofit2.Call;

@SuppressWarnings("UnusedReturnValue")
public class NotesRepository {

    private static final String TAG = NotesRepository.class.getSimpleName();

    private static NotesRepository instance;

    private final ApiProvider apiProvider;
    private ExecutorService executor;
    private final ExecutorService syncExecutor;
    private final ExecutorService importExecutor;
    private Context context;
    private final NotesDatabase db;
    private final String defaultNonEmptyTitle;
    private final LiveData<ConnectionLiveData.ConnectionType> connectionLiveData;
    private boolean isSyncPossible = false;
    private boolean networkConnected = false;
    private String syncOnlyOnWifiKey;
    private boolean syncOnlyOnWifi;
    private final MutableLiveData<Boolean> syncStatus = new MutableLiveData<>(false);
    private final MutableLiveData<ArrayList<Throwable>> syncErrors = new MutableLiveData<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Observer<? super ConnectionLiveData.ConnectionType> syncObserver = (Observer<ConnectionLiveData.ConnectionType>) connectionType -> {
        observeNetworkStatus(connectionType);

        if (context == null || executor == null) {
            return;
        }

        if (isSyncPossible() && SSOUtil.isConfigured(context)) {
            executor.submit(() -> {
                try {
                    scheduleSync(getAccountByName(SingleAccountHelper.getCurrentSingleSignOnAccount(context).name), false);
                } catch (NextcloudFilesAppAccountNotFoundException |
                         NoCurrentAccountSelectedException e) {
                    Log.v(TAG, "Can not select current SingleSignOn account after network changed, do not sync.");
                }
            });
        }
    };

    /**
     * @see <a href="https://stackoverflow.com/a/3104265">Do not make this a local variable.</a>
     */
    @SuppressWarnings("FieldCanBeLocal")
    private final SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener = (SharedPreferences prefs, String key) -> {
        if (syncOnlyOnWifiKey.equals(key)) {
            syncOnlyOnWifi = prefs.getBoolean(syncOnlyOnWifiKey, false);
            updateNetworkStatus();
        }
    };

    // current state of the synchronization
    private final Map<Long, Boolean> syncActive = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> syncScheduled = new ConcurrentHashMap<>();

    // list of callbacks for both parts of synchronization
    private final Map<Long, List<ISyncCallback>> callbacksPush = new ConcurrentHashMap<>();
    private final Map<Long, List<ISyncCallback>> callbacksPull = new ConcurrentHashMap<>();


    public static synchronized NotesRepository getInstance(@NonNull Context context) {
        if (instance == null) {
            instance = new NotesRepository(context, NotesDatabase.getInstance(context.getApplicationContext()), Executors.newCachedThreadPool(), Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor(), ApiProvider.getInstance());
        }
        return instance;
    }

    private NotesRepository(@NonNull final Context context, @NonNull final NotesDatabase db, @NonNull final ExecutorService executor, @NonNull final ExecutorService syncExecutor, @NonNull final ExecutorService importExecutor, @NonNull ApiProvider apiProvider) {
        this.context = context.getApplicationContext();
        this.db = db;
        this.executor = executor;
        this.syncExecutor = syncExecutor;
        this.importExecutor = importExecutor;
        this.apiProvider = apiProvider;
        this.defaultNonEmptyTitle = NoteUtil.generateNonEmptyNoteTitle("", this.context);
        this.syncOnlyOnWifiKey = context.getApplicationContext().getResources().getString(R.string.pref_key_wifi_only);
        this.connectionLiveData = new ConnectionLiveData(context);

        mainHandler.post(() -> connectionLiveData.observeForever(syncObserver));

        final var prefs = PreferenceManager.getDefaultSharedPreferences(this.context);
        prefs.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
        syncOnlyOnWifi = prefs.getBoolean(syncOnlyOnWifiKey, false);

        updateNetworkStatus();
    }

    public void updateNetworkStatus() {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager == null) {
            handleFailedNetworkStatus("ConnectivityManager is null");
            return;
        }

        Network network = connectivityManager.getActiveNetwork();
        if (network == null) {
            handleFailedNetworkStatus("No network connection");
            return;
        }

        NetworkCapabilities networkCapabilities = connectivityManager.getNetworkCapabilities(network);
        if (networkCapabilities == null) {
            handleFailedNetworkStatus("NetworkCapabilities is null");
            return;
        }

        handleNetworkStatus(networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
    }

    private void observeNetworkStatus(ConnectionLiveData.ConnectionType connectionType) {
        if (connectionType == ConnectionLiveData.ConnectionType.Lost) {
            handleFailedNetworkStatus("No network connection");
        } else {
            Log.d(TAG, "Network connection established with " + connectionType.name());
            handleNetworkStatus(connectionType == ConnectionLiveData.ConnectionType.WiFi);
        }
    }

    private void handleFailedNetworkStatus(String message) {
        Log.e(TAG, message);
        networkConnected = false;
        isSyncPossible = false;
    }

    private void handleNetworkStatus(boolean isWifiActive) {
        networkConnected = true;
        isSyncPossible = !syncOnlyOnWifi || isWifiActive;

        if (isSyncPossible) {
            Log.d(TAG, "Network connection established.");
        } else {
            Log.d(TAG, "Network connected, but not used because only synced on wifi.");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        mainHandler.post(() -> connectionLiveData.removeObserver(syncObserver));
        super.finalize();
    }

    // Accounts
    @AnyThread
    public LiveData<ImportStatus> addAccount(@NonNull String url, @NonNull String username, @NonNull String accountName, @NonNull Capabilities capabilities, @Nullable String displayName, @NonNull IResponseCallback<Account> callback) {
        db.getCapabilitiesDao().insert(capabilities);

        final var account = db.getAccountDao().getAccountById(db.getAccountDao().insert(new Account(url, username, accountName, displayName, capabilities)));
        if (account == null) {
            callback.onError(new Exception("Could not read created account."));
        } else {
            if (isSyncPossible()) {
                syncActive.put(account.getId(), true);
                try {
                    Log.d(TAG, "… starting now");
                    final NotesImportTask importTask = new NotesImportTask(context, this, account, importExecutor, apiProvider);
                    return importTask.importNotes(new IResponseCallback<>() {
                        @Override
                        public void onSuccess(Void result) {
                            callback.onSuccess(account);
                        }

                        @Override
                        public void onError(@NonNull Throwable t) {
                            Log.e(TAG, "… Error while importing " + account.getAccountName() + ": " + t.getMessage());
                            deleteAccount(account);
                            SingleAccountHelper.commitCurrentAccount(context, null);
                            callback.onError(t);
                        }
                    });
                } catch (NextcloudFilesAppAccountNotFoundException e) {
                    Log.e(TAG, "… Could not find " + SingleSignOnAccount.class.getSimpleName() + " for account name " + account.getAccountName());
                    importExecutor.submit(() -> {
                        deleteAccount(account);
                        SingleAccountHelper.commitCurrentAccount(context, null);
                        callback.onError(e);
                    });
                }
            } else {
                Log.e(TAG, "… No network connection available to import " + account.getAccountName());
                importExecutor.submit(() -> {
                    deleteAccount(account);
                    SingleAccountHelper.commitCurrentAccount(context, null);
                    callback.onError(new NetworkErrorException());
                });
            }
        }
        return new MutableLiveData<>(new ImportStatus());
    }

    @WorkerThread
    public List<Account> getAccounts() {
        return db.getAccountDao().getAccounts();
    }

    @WorkerThread
    public void deleteAccount(@NonNull Account account) {
        try {
            apiProvider.invalidateAPICache(AccountImporter.getSingleSignOnAccount(context, account.getAccountName()));
        } catch (NextcloudFilesAppAccountNotFoundException e) {
            e.printStackTrace();
            apiProvider.invalidateAPICache();
        }

        db.getAccountDao().deleteAccount(account);
    }

    public Capabilities getCapabilities() {
        return db.getCapabilitiesDao().getCapabilities();
    }

    public Account getAccountByName(String accountName) {
        return db.getAccountDao().getAccountByName(accountName);
    }

    public Account getAccountById(long accountId) {
        return db.getAccountDao().getAccountById(accountId);
    }

    public LiveData<List<Account>> getAccounts$() {
        return db.getAccountDao().getAccounts$();
    }

    public LiveData<Account> getAccountById$(long accountId) {
        return db.getAccountDao().getAccountById$(accountId);
    }

    public LiveData<Integer> countAccounts$() {
        return db.getAccountDao().countAccounts$();
    }

    public void updateBrand(long id, @ColorInt Integer color) {
        db.getAccountDao().updateBrand(id, color);
    }

    public void updateETag(long id, String eTag) {
        db.getAccountDao().updateETag(id, eTag);
    }

    public void updateCapabilitiesETag(long id, String capabilitiesETag) {
        db.getAccountDao().updateCapabilitiesETag(id, capabilitiesETag);
    }

    public void insertCapabilities(Capabilities capabilities) {
        db.getCapabilitiesDao().insert(capabilities);
    }

    public void updateModified(long id, long modified) {
        db.getAccountDao().updateModified(id, modified);
    }

    public void updateDirectEditingAvailable(final long id, final boolean available) {
        db.getAccountDao().updateDirectEditingAvailable(id, available);
    }

    // Notes

    public LiveData<Note> getNoteById$(long id) {
        return db.getNoteDao().getNoteById$(id);
    }

    public Note getNoteById(long id) {
        return db.getNoteDao().getNoteById(id);
    }

    public LiveData<Integer> count$(long accountId) {
        return db.getNoteDao().count$(accountId);
    }

    public LiveData<Integer> countFavorites$(long accountId) {
        return db.getNoteDao().countFavorites$(accountId);
    }

    public void updateScrollY(long id, int scrollY) {
        db.getNoteDao().updateScrollY(id, scrollY);
    }

    public LiveData<List<CategoryWithNotesCount>> searchCategories$(Long accountId, String searchTerm) {
        return db.getNoteDao().searchCategories$(accountId, searchTerm);
    }

    public LiveData<List<Note>> searchRecentByModified$(long accountId, String query) {
        return db.getNoteDao().searchRecentByModified$(accountId, query);
    }

    public List<Note> searchRecentByModified(long accountId, String query) {
        return db.getNoteDao().searchRecentByModified(accountId, query);
    }

    public LiveData<List<Note>> searchRecentLexicographically$(long accountId, String query) {
        return db.getNoteDao().searchRecentLexicographically$(accountId, query);
    }

    public LiveData<List<Note>> searchFavoritesByModified$(long accountId, String query) {
        return db.getNoteDao().searchFavoritesByModified$(accountId, query);
    }

    public List<Note> searchFavoritesByModified(long accountId, String query) {
        return db.getNoteDao().searchFavoritesByModified(accountId, query);
    }

    public LiveData<List<Note>> searchFavoritesLexicographically$(long accountId, String query) {
        return db.getNoteDao().searchFavoritesLexicographically$(accountId, query);
    }

    public LiveData<List<Note>> searchUncategorizedByModified$(long accountId, String query) {
        return db.getNoteDao().searchUncategorizedByModified$(accountId, query);
    }

    public List<Note> searchUncategorizedByModified(long accountId, String query) {
        return db.getNoteDao().searchUncategorizedByModified(accountId, query);
    }

    public LiveData<List<Note>> searchUncategorizedLexicographically$(long accountId, String query) {
        return db.getNoteDao().searchUncategorizedLexicographically$(accountId, query);
    }

    public LiveData<List<Note>> searchCategoryByModified$(long accountId, String query, String category) {
        return db.getNoteDao().searchCategoryByModified$(accountId, query, category);
    }

    public List<Note> searchCategoryByModified(long accountId, String query, String category) {
        return db.getNoteDao().searchCategoryByModified(accountId, query, category);
    }

    public LiveData<List<Note>> searchCategoryLexicographically$(long accountId, String query, String category) {
        return db.getNoteDao().searchCategoryLexicographically$(accountId, query, category);
    }

    public LiveData<List<CategoryWithNotesCount>> getCategories$(Long accountId) {
        return db.getNoteDao().getCategories$(accountId);
    }

    public void updateRemoteId(long id, Long remoteId) {
        db.getNoteDao().updateRemoteId(id, remoteId);
    }

    public Long getLocalIdByRemoteId(long accountId, long remoteId) {
        return db.getNoteDao().getLocalIdByRemoteId(accountId, remoteId);
    }

    public List<Note> getLocalModifiedNotes(long accountId) {
        return db.getNoteDao().getLocalModifiedNotes(accountId);
    }

    public void deleteByNoteId(long id, DBStatus forceDBStatus) {
        db.getNoteDao().deleteByNoteId(id, forceDBStatus);
    }

    /**
     * Please note, that db.updateNote() realized an optimistic conflict resolution, which is required for parallel changes of this Note from the UI.
     */
    public int updateIfNotModifiedLocallyDuringSync(long noteId, Long targetModified, String targetTitle, boolean targetFavorite, String targetETag, String targetContent, String targetExcerpt, String contentBeforeSyncStart, String categoryBeforeSyncStart, boolean favoriteBeforeSyncStart) {
        return db.getNoteDao().updateIfNotModifiedLocallyDuringSync(noteId, targetModified, targetTitle, targetFavorite, targetETag, targetContent, targetExcerpt, contentBeforeSyncStart, categoryBeforeSyncStart, favoriteBeforeSyncStart);
    }

    public int updateIfNotModifiedLocallyAndAnyRemoteColumnHasChanged(long id, Long modified, String title, boolean favorite, String category, String eTag, String content, String excerpt) {
        return db.getNoteDao().updateIfNotModifiedLocallyAndAnyRemoteColumnHasChanged(id, modified, title, favorite, category, eTag, content, excerpt);
    }

    public long countUnsynchronizedNotes(long accountId) {
        final Long unsynchronizedNotesCount = db.getNoteDao().countUnsynchronizedNotes(accountId);
        return unsynchronizedNotesCount == null ? 0 : unsynchronizedNotesCount;
    }


    // SingleNoteWidget

    public void createOrUpdateSingleNoteWidgetData(SingleNoteWidgetData data) {
        db.getWidgetSingleNoteDao().createOrUpdateSingleNoteWidgetData(data);
    }

    public void removeSingleNoteWidget(int id) {
        db.getWidgetSingleNoteDao().removeSingleNoteWidget(id);
    }

    public SingleNoteWidgetData getSingleNoteWidgetData(int id) {
        return db.getWidgetSingleNoteDao().getSingleNoteWidgetData(id);
    }


    // ListWidget

    public void createOrUpdateNoteListWidgetData(NotesListWidgetData data) {
        db.getWidgetNotesListDao().createOrUpdateNoteListWidgetData(data);
    }

    public void removeNoteListWidget(int appWidgetId) {
        db.getWidgetNotesListDao().removeNoteListWidget(appWidgetId);
    }

    public NotesListWidgetData getNoteListWidgetData(int appWidgetId) {
        return db.getWidgetNotesListDao().getNoteListWidgetData(appWidgetId);
    }

    /**
     * Creates a new Note in the Database and adds a Synchronization Flag.
     *
     * @param note Note
     */
    @NonNull
    @MainThread
    public LiveData<Note> addNoteAndSync(Account account, Note note) {
        final var entity = new Note(0, null, note.getModified(), note.getTitle(), note.getContent(), note.getCategory(), note.getFavorite(), note.getETag(), DBStatus.LOCAL_EDITED, account.getId(), generateNoteExcerpt(note.getContent(), note.getTitle()), 0, note.isShared(), note.getReadonly());
        final var ret = new MutableLiveData<Note>();
        executor.submit(() -> ret.postValue(addNote(account.getId(), entity)));
        return map(ret, newNote -> {
            notifyWidgets();
            scheduleSync(account, true);
            return newNote;
        });
    }

    /**
     * Inserts a note directly into the Database.
     * Excerpt will be generated, {@link DBStatus#LOCAL_EDITED} will be applied in case the note has
     * already has a local ID, otherwise {@link DBStatus#VOID} will be applied.
     * No Synchronisation will be triggered! Use {@link #addNoteAndSync(Account, Note)}!
     *
     * @param note {@link Note} to be added.
     */
    @NonNull
    @WorkerThread
    public Note addNote(long accountId, @NonNull Note note) {
        note.setAccountId(accountId);
        note.setExcerpt(generateNoteExcerpt(note.getContent(), note.getTitle()));
        return db.getNoteDao().getNoteById(db.getNoteDao().addNote(note));
    }

    @MainThread
    public LiveData<Note> moveNoteToAnotherAccount(Account account, @NonNull Note note) {
        final var fullNote = new Note(null, note.getModified(), note.getTitle(), note.getContent(), note.getCategory(), note.getFavorite(), null, note.isShared(), note.getReadonly());
        fullNote.setStatus(DBStatus.LOCAL_EDITED);
        deleteNoteAndSync(account, note.getId());
        return addNoteAndSync(account, fullNote);
    }

    /**
     * @return a {@link Map} of remote IDs as keys and local IDs as values of all {@link Note}s of
     * the given {@param accountId} which are not {@link DBStatus#LOCAL_DELETED}
     */
    @NonNull
    @WorkerThread
    public Map<Long, Long> getIdMap(long accountId) {
        return db.getNoteDao()
                .getRemoteIdAndId(accountId)
                .stream()
                .collect(toMap(Note::getRemoteId, Note::getId));
    }

    @AnyThread
    public void toggleFavoriteAndSync(Account account, Note note) {
        executor.submit(() -> {
            try {
                final var noteWithContent = getNoteById(note.getId());
                if (noteWithContent == null) {
                    return;
                }

                final var ssoAccount = AccountImporter.getSingleSignOnAccount(context, account.getAccountName());
                final var notesAPI = apiProvider.getNotesAPI(context, ssoAccount, getPreferredApiVersion(account.getApiVersion()));
                noteWithContent.setFavorite(!noteWithContent.getFavorite());
                final var result = notesAPI.updateNote(noteWithContent);
                final var response = result.execute();
                if (response.isSuccessful()) {
                    final var updatedNote = response.body();
                    if (updatedNote != null) {
                        scheduleSync(account, false);
                    }
                }
            } catch (Exception e) {
                Log_OC.e(TAG, "toggleFavoriteAndSync: " + e);
            }
        });
    }

    /**
     * Set the category for a given note.
     * This method will search in the database to find out the category id in the db.
     * If there is no such category existing, this method will create it and search again.
     *
     * @param account  The single sign on account
     * @param noteId   The note which will be updated
     * @param category The category title which should be used to find the category id.
     */
    @AnyThread
    public void setCategory(@NonNull Account account, long noteId, @NonNull String category) {
        executor.submit(() -> {
            db.getNoteDao().updateStatus(noteId, DBStatus.LOCAL_EDITED);
            db.getNoteDao().updateCategory(noteId, category);
            scheduleSync(account, true);
        });
    }

    /**
     * Updates a single Note with a new content.
     * The title is derived from the new content automatically, and modified date as well as DBStatus are updated, too -- if the content differs to the state in the database.
     *
     * @param oldNote    Note to be changed
     * @param newContent New content. If this is <code>null</code>, then <code>oldNote</code> is saved again (useful for undoing changes).
     * @param newTitle   New title. If this is <code>null</code>, then either the old title is reused (in case the note has been synced before) or a title is generated (in case it is a new note)
     * @param callback   When the synchronization is finished, this callback will be invoked (optional).
     * @return changed {@link Note} if differs from database, otherwise the old {@link Note}.
     */
    @WorkerThread
    public Note updateNoteAndSync(@NonNull Account localAccount, @NonNull Note oldNote, @Nullable String newContent, @Nullable String newTitle, @Nullable ISyncCallback callback) {
        final Note newNote;
        // Re-read the up to date remoteId from the database because the UI might not have the state after synchronization yet
        // https://github.com/nextcloud/notes-android/issues/1198
        @Nullable final Long remoteId = db.getNoteDao().getRemoteId(oldNote.getId());
        if (newContent == null) {
            newNote = new Note(oldNote.getId(), remoteId, oldNote.getModified(), oldNote.getTitle(), oldNote.getContent(), oldNote.getCategory(), oldNote.getFavorite(), oldNote.getETag(), DBStatus.LOCAL_EDITED, localAccount.getId(), oldNote.getExcerpt(), oldNote.getScrollY(), oldNote.isShared(), oldNote.getReadonly());
        } else {
            final String title;
            if (newTitle != null) {
                title = newTitle;
            } else {
                final ApiVersion preferredApiVersion = ApiVersionUtil.getPreferredApiVersion(localAccount.getApiVersion());
                if ((remoteId == null || preferredApiVersion == null || preferredApiVersion.compareTo(ApiVersion.API_VERSION_1_0) < 0) &&
                        (defaultNonEmptyTitle.equals(oldNote.getTitle()))) {
                    title = NoteUtil.generateNonEmptyNoteTitle(newContent, context);
                } else {
                    title = oldNote.getTitle();
                }
            }
            newNote = new Note(oldNote.getId(), remoteId, Calendar.getInstance(), title, newContent, oldNote.getCategory(), oldNote.getFavorite(), oldNote.getETag(), DBStatus.LOCAL_EDITED, localAccount.getId(), generateNoteExcerpt(newContent, title), oldNote.getScrollY(), oldNote.isShared(), oldNote.getReadonly());
        }
        int rows = db.getNoteDao().updateNote(newNote);
        // if data was changed, set new status and schedule sync (with callback); otherwise invoke callback directly.
        if (rows > 0) {
            notifyWidgets();
            if (callback != null) {
                addCallbackPush(localAccount, callback);
            }
            scheduleSync(localAccount, true);
            return newNote;
        } else {
            if (callback != null) {
                callback.onFinish();
            }
            return oldNote;
        }
    }

    /**
     * Marks a Note in the Database as Deleted. In the next Synchronization it will be deleted
     * from the Server.
     *
     * @param id long - ID of the Note that should be deleted
     */
    @AnyThread
    public void deleteNoteAndSync(Account account, long id) {
        executor.submit(() -> {
            db.getNoteDao().updateStatus(id, DBStatus.LOCAL_DELETED);
            notifyWidgets();
            scheduleSync(account, true);

            if (SDK_INT >= O) {
                final var shortcutManager = context.getSystemService(ShortcutManager.class);
                if (shortcutManager != null) {
                    shortcutManager.getPinnedShortcuts().forEach((shortcut) -> {
                        final String shortcutId = String.valueOf(id);
                        if (shortcut.getId().equals(shortcutId)) {
                            Log.v(TAG, "Removing shortcut for " + shortcutId);
                            shortcutManager.disableShortcuts(Collections.singletonList(shortcutId), context.getResources().getString(R.string.note_has_been_deleted));
                        }
                    });
                } else {
                    Log.e(TAG, ShortcutManager.class.getSimpleName() + "is null.");
                }
            }
        });
    }

    /**
     * Notify about changed notes.
     */
    @AnyThread
    private void notifyWidgets() {
        executor.submit(() -> {
            updateSingleNoteWidgets(context);
            updateNoteListWidgets(context);
        });
    }

    @AnyThread
    private void updateDynamicShortcuts(long accountId) {
        executor.submit(() -> {
            if (SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                final var shortcutManager = this.context.getSystemService(ShortcutManager.class);
                if (shortcutManager != null) {
                    if (!shortcutManager.isRateLimitingActive()) {
                        var newShortcuts = new ArrayList<ShortcutInfo>();

                        for (final var note : db.getNoteDao().getRecentNotes(accountId)) {
                            if (!TextUtils.isEmpty(note.getTitle())) {
                                final var intent = new Intent(this.context, EditNoteActivity.class);
                                intent.putExtra(EditNoteActivity.PARAM_NOTE_ID, note.getId());
                                intent.setAction(ACTION_SHORTCUT);

                                newShortcuts.add(new ShortcutInfo.Builder(this.context, note.getId() + "")
                                        .setShortLabel(note.getTitle() + "")
                                        .setIcon(Icon.createWithResource(this.context, note.getFavorite() ? R.drawable.ic_star_yellow_24dp : R.drawable.ic_star_border_grey_ccc_24dp))
                                        .setIntent(intent)
                                        .build());
                            } else {
                                // Prevent crash https://github.com/nextcloud/notes-android/issues/613
                                Log.e(TAG, "shortLabel cannot be empty " + (BuildConfig.DEBUG ? note : note.getTitle()));
                            }
                        }
                        Log.d(TAG, "Update dynamic shortcuts");
                        shortcutManager.removeAllDynamicShortcuts();
                        shortcutManager.addDynamicShortcuts(newShortcuts);
                    }
                }
            }
        });
    }

    /**
     * @param raw has to be a JSON array as a string <code>["0.2", "1.0", ...]</code>
     */
    public void updateApiVersion(long accountId, @Nullable String raw) {
        final var apiVersions = ApiVersionUtil.parse(raw);
        if (apiVersions.size() > 0) {
            final int updatedRows = db.getAccountDao().updateApiVersion(accountId, ApiVersionUtil.serialize(apiVersions));
            if (updatedRows == 0) {
                Log.d(TAG, "ApiVersion not updated, because it did not change");
            } else if (updatedRows == 1) {
                Log.i(TAG, "Updated apiVersion to \"" + raw + "\" for accountId = " + accountId);
                apiProvider.invalidateAPICache();
            } else {
                Log.w(TAG, "Updated " + updatedRows + " but expected only 1 for accountId = " + accountId + " and apiVersion = \"" + raw + "\"");
            }
        } else {
            Log.v(TAG, "Could not extract any version from the given String: " + raw);
        }
    }

    /**
     * Modifies the sorting method for one category, the category can be normal category or
     * one of "All notes", "Favorite", and "Uncategorized".
     * If category is one of these three, sorting method will be modified in android.content.SharedPreference.
     * The user can determine use which sorting method to show the notes for a category.
     * When the user changes the sorting method, this method should be called.
     *
     * @param accountId        The user accountID
     * @param selectedCategory The category to be modified
     * @param sortingMethod    The sorting method in {@link CategorySortingMethod} enum format
     */
    @AnyThread
    public void modifyCategoryOrder(long accountId, @NonNull NavigationCategory selectedCategory, @NonNull CategorySortingMethod sortingMethod) {
        executor.submit(() -> {
            final var ctx = context.getApplicationContext();
            final var sp = PreferenceManager.getDefaultSharedPreferences(ctx).edit();
            int orderIndex = sortingMethod.getId();

            switch (selectedCategory.getType()) {
                case FAVORITES -> {
                    sp.putInt(ctx.getString(R.string.action_sorting_method) + ' ' + ctx.getString(R.string.label_favorites), orderIndex);
                }
                case UNCATEGORIZED -> {
                    sp.putInt(ctx.getString(R.string.action_sorting_method) + ' ' + ctx.getString(R.string.action_uncategorized), orderIndex);
                }
                case RECENT -> {
                    sp.putInt(ctx.getString(R.string.action_sorting_method) + ' ' + ctx.getString(R.string.label_all_notes), orderIndex);
                }
                default -> {
                    final String category = selectedCategory.getCategory();
                    if (category != null) {
                        if (db.getCategoryOptionsDao().modifyCategoryOrder(accountId, category, sortingMethod) == 0) {
                            // Nothing updated means we didn't have this yet
                            final var categoryOptions = new CategoryOptions();
                            categoryOptions.setAccountId(accountId);
                            categoryOptions.setCategory(category);
                            categoryOptions.setSortingMethod(sortingMethod);
                            db.getCategoryOptionsDao().addCategoryOptions(categoryOptions);
                        }
                    } else {
                        throw new IllegalStateException("Tried to modify category order for " + ENavigationCategoryType.DEFAULT_CATEGORY + "but category is null.");
                    }
                }
            }
            sp.apply();
        });
    }

    /**
     * Gets the sorting method of a {@link NavigationCategory}, the category can be normal
     * {@link CategoryOptions} or one of {@link ENavigationCategoryType}.
     * If the category no normal {@link CategoryOptions}, sorting method will be got from
     * {@link SharedPreferences}.
     * <p>
     * The sorting method of the category can be used to decide to use which sorting method to show
     * the notes for each categories.
     *
     * @param selectedCategory The category
     * @return The sorting method in CategorySortingMethod enum format
     */
    @NonNull
    @MainThread
    public LiveData<CategorySortingMethod> getCategoryOrder(@NonNull NavigationCategory selectedCategory) {
        final var sp = PreferenceManager.getDefaultSharedPreferences(context);
        String prefKey;

        switch (selectedCategory.getType()) {
            // TODO make this account specific
            case RECENT -> {
                prefKey = context.getString(R.string.action_sorting_method) + ' ' + context.getString(R.string.label_all_notes);
            }
            case FAVORITES -> {
                prefKey = context.getString(R.string.action_sorting_method) + ' ' + context.getString(R.string.label_favorites);
            }
            case UNCATEGORIZED -> {
                prefKey = context.getString(R.string.action_sorting_method) + ' ' + context.getString(R.string.action_uncategorized);
            }
            default -> {
                final String category = selectedCategory.getCategory();
                if (category != null) {
                    return db.getCategoryOptionsDao().getCategoryOrder(selectedCategory.getAccountId(), category);
                } else {
                    Log.e(TAG, "Cannot read " + CategorySortingMethod.class.getSimpleName() + " for " + ENavigationCategoryType.DEFAULT_CATEGORY + ".");
                    return new MutableLiveData<>(CategorySortingMethod.SORT_MODIFIED_DESC);
                }
            }
        }

        return map(new SharedPreferenceIntLiveData(sp, prefKey, CategorySortingMethod.SORT_MODIFIED_DESC.getId()), CategorySortingMethod::findById);
    }

    /**
     * Synchronization is only possible, if there is an active network connection.
     * <p>
     * This method respects the user preference "Sync on Wi-Fi only".
     * <p>
     * NoteServerSyncHelper observes changes in the network connection.
     * The current state can be retrieved with this method.
     *
     * @return true if sync is possible, otherwise false.
     */
    public boolean isSyncPossible() {
        return isSyncPossible;
    }

    public boolean isNetworkConnected() {
        return networkConnected;
    }

    public boolean isSyncOnlyOnWifi() {
        return syncOnlyOnWifi;
    }

    /**
     * Adds a callback method to the NoteServerSyncHelper for the synchronization part push local changes to the server.
     * All callbacks will be executed once the synchronization operations are done.
     * After execution the callback will be deleted, so it has to be added again if it shall be
     * executed the next time all synchronize operations are finished.
     *
     * @param callback Implementation of ISyncCallback, contains one method that shall be executed.
     */
    private void addCallbackPush(Account account, ISyncCallback callback) {
        if (account == null) {
            Log.i(TAG, "ssoAccount is null. Is this a local account?");
            callback.onScheduled();
            callback.onFinish();
        } else {
            if (!callbacksPush.containsKey(account.getId())) {
                callbacksPush.put(account.getId(), new ArrayList<>());
            }
            Objects.requireNonNull(callbacksPush.get(account.getId())).add(callback);
        }
    }

    /**
     * Adds a callback method to the NoteServerSyncHelper for the synchronization part pull remote changes from the server.
     * All callbacks will be executed once the synchronization operations are done.
     * After execution the callback will be deleted, so it has to be added again if it shall be
     * executed the next time all synchronize operations are finished.
     *
     * @param callback Implementation of ISyncCallback, contains one method that shall be executed.
     */
    public void addCallbackPull(Account account, ISyncCallback callback) {
        if (account == null) {
            Log.i(TAG, "ssoAccount is null. Is this a local account?");
            callback.onScheduled();
            callback.onFinish();
        } else {
            if (!callbacksPull.containsKey(account.getId())) {
                callbacksPull.put(account.getId(), new ArrayList<>());
            }
            Objects.requireNonNull(callbacksPull.get(account.getId())).add(callback);
        }
    }

    /**
     * Schedules a synchronization and start it directly, if the network is connected and no
     * synchronization is currently running.
     *
     * @param onlyLocalChanges Whether to only push local changes to the server or to also load the whole list of notes from the server.
     */
    public synchronized void scheduleSync(@Nullable Account account, boolean onlyLocalChanges) {
        if (account == null) {
            Log.i(TAG, SingleSignOnAccount.class.getSimpleName() + " is null. Is this a local account?");
        } else {
            syncActive.putIfAbsent(account.getId(), false);
            Log.d(TAG, "Sync requested (" + (onlyLocalChanges ? "onlyLocalChanges" : "full") + "; " + (Boolean.TRUE.equals(syncActive.get(account.getId())) ? "sync active" : "sync NOT active") + ") ...");
            if (isSyncPossible() && (!Boolean.TRUE.equals(syncActive.get(account.getId())) || onlyLocalChanges)) {
                syncActive.put(account.getId(), true);
                try {
                    Log.d(TAG, "... starting now");
                    final NotesServerSyncTask syncTask = new NotesServerSyncTask(context, this, account, onlyLocalChanges, apiProvider) {
                        @Override
                        void onPreExecute() {
                            syncStatus.postValue(true);
                            if (!syncScheduled.containsKey(localAccount.getId()) || syncScheduled.get(localAccount.getId()) == null) {
                                syncScheduled.put(localAccount.getId(), false);
                            }
                            if (!onlyLocalChanges && Boolean.TRUE.equals(syncScheduled.get(localAccount.getId()))) {
                                syncScheduled.put(localAccount.getId(), false);
                            }
                        }

                        @Override
                        void onPostExecute(SyncResultStatus status) {
                            for (Throwable e : exceptions) {
                                Log.e(TAG, e.getMessage(), e);
                            }
                            if (!status.pullSuccessful || !status.pushSuccessful) {
                                syncErrors.postValue(exceptions);
                            }
                            syncActive.put(localAccount.getId(), false);
                            // notify callbacks
                            if (callbacks.containsKey(localAccount.getId()) && callbacks.get(localAccount.getId()) != null) {
                                for (ISyncCallback callback : Objects.requireNonNull(callbacks.get(localAccount.getId()))) {
                                    callback.onFinish();
                                }
                            }
                            notifyWidgets();
                            updateDynamicShortcuts(localAccount.getId());
                            // start next sync if scheduled meanwhile
                            if (syncScheduled.containsKey(localAccount.getId()) && syncScheduled.get(localAccount.getId()) != null && Boolean.TRUE.equals(syncScheduled.get(localAccount.getId()))) {
                                scheduleSync(localAccount, false);
                            }
                            syncStatus.postValue(false);
                        }
                    };
                    syncTask.addCallbacks(account, callbacksPush.get(account.getId()));
                    callbacksPush.put(account.getId(), new ArrayList<>());
                    if (!onlyLocalChanges) {
                        syncTask.addCallbacks(account, callbacksPull.get(account.getId()));
                        callbacksPull.put(account.getId(), new ArrayList<>());
                    }
                    syncExecutor.submit(syncTask);
                } catch (NextcloudFilesAppAccountNotFoundException e) {
                    Log.e(TAG, "... Could not find " + SingleSignOnAccount.class.getSimpleName() + " for account name " + account.getAccountName());
                    e.printStackTrace();
                }
            } else if (!onlyLocalChanges) {
                Log.d(TAG, "... scheduled");
                syncScheduled.put(account.getId(), true);
                if (callbacksPush.containsKey(account.getId()) && callbacksPush.get(account.getId()) != null) {
                    final var callbacks = callbacksPush.get(account.getId());
                    if (callbacks != null) {
                        for (final var callback : callbacks) {
                            callback.onScheduled();
                        }
                    } else {
                        Log.w(TAG, "List of push-callbacks was set for account \"" + account.getAccountName() + "\" but it was null");
                    }
                }
            } else {
                Log.d(TAG, "... do nothing");
                if (callbacksPush.containsKey(account.getId()) && callbacksPush.get(account.getId()) != null) {
                    final var callbacks = callbacksPush.get(account.getId());
                    if (callbacks != null) {
                        for (final var callback : callbacks) {
                            callback.onScheduled();
                        }
                    } else {
                        Log.w(TAG, "List of push-callbacks was set for account \"" + account.getAccountName() + "\" but it was null");
                    }
                }
            }
        }
    }

    @NonNull
    public LiveData<Boolean> getSyncStatus() {
        return distinctUntilChanged(this.syncStatus);
    }

    @NonNull
    public LiveData<ArrayList<Throwable>> getSyncErrors() {
        return this.syncErrors;
    }

    public Call<NotesSettings> getServerSettings(@NonNull SingleSignOnAccount ssoAccount, @Nullable ApiVersion preferredApiVersion) {
        return ApiProvider.getInstance().getNotesAPI(context, ssoAccount, preferredApiVersion).getSettings();
    }

    public Call<NotesSettings> putServerSettings(@NonNull SingleSignOnAccount ssoAccount, @NonNull NotesSettings settings, @Nullable ApiVersion preferredApiVersion) {
        return ApiProvider.getInstance().getNotesAPI(context, ssoAccount, preferredApiVersion).putSettings(settings);
    }

    public void updateDisplayName(long id, @Nullable String displayName) {
        db.getAccountDao().updateDisplayName(id, displayName);
    }

    public void addShareEntities(List<ShareEntity> entities) {
        db.getShareDao().addShareEntities(entities);
    }

    public List<ShareEntity> getShareEntities(String path) {
        return db.getShareDao().getShareEntities(path);
    }

    public void updateNote(Note note) {
        db.getNoteDao().updateNote(note);
    }
}
