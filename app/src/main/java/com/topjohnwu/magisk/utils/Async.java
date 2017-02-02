package com.topjohnwu.magisk.utils;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.provider.OpenableColumns;
import android.widget.Toast;

import com.topjohnwu.magisk.Global;
import com.topjohnwu.magisk.R;
import com.topjohnwu.magisk.adapters.ApplicationAdapter;
import com.topjohnwu.magisk.module.ModuleHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class Async {

    public abstract static class RootTask<Params, Progress, Result> extends AsyncTask<Params, Progress, Result> {
        @SafeVarargs
        public final void exec(Params... params) {
            if (!Shell.rootAccess()) return;
            executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, params);
        }
    }

    public abstract static class NormalTask<Params, Progress, Result> extends AsyncTask<Params, Progress, Result> {
        @SafeVarargs
        public final void exec(Params... params) {
            executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, params);
        }
    }

    public static final String UPDATE_JSON = "https://raw.githubusercontent.com/topjohnwu/MagiskManager/updates/magisk_update.json";
    public static final String MAGISK_HIDE_PATH = "/magisk/.core/magiskhide/";
    public static final String TMP_FOLDER_PATH = "/dev/tmp";

    public static class InitConfigs extends RootTask<Void, Void, Void> {

        Context mContext;

        public InitConfigs(Context context) {
            mContext = context;
        }

        @Override
        protected Void doInBackground(Void... params) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
            Global.Configs.isDarkTheme = prefs.getBoolean("dark_theme", false);
            Global.Configs.devLogging = prefs.getBoolean("developer_logging", false);
            Global.Configs.shellLogging = prefs.getBoolean("shell_logging", false);
            Global.Configs.magiskHide = prefs.getBoolean("magiskhide", false);
            Global.updateMagiskInfo();
            Global.initSuAccess();
            Global.initSuConfigs(mContext);
            // Initialize prefs
            prefs.edit()
                    .putBoolean("dark_theme", Global.Configs.isDarkTheme)
                    .putBoolean("magiskhide", Global.Configs.magiskHide)
                    .putBoolean("busybox", Utils.commandExists("busybox"))
                    .putBoolean("hosts", Utils.itemExist(false, "/magisk/.core/hosts"))
                    .putBoolean("disable", Utils.itemExist(Global.MAGISK_DISABLE_FILE))
                    .putString("su_request_timeout", String.valueOf(Global.Configs.suRequestTimeout))
                    .putString("su_auto_response", String.valueOf(Global.Configs.suResponseType))
                    .putString("su_notification", String.valueOf(Global.Configs.suNotificationType))
                    .putString("su_access", String.valueOf(Global.Configs.suAccessState))
                    .apply();
            return null;
        }
    }

    public static class CheckUpdates extends NormalTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... voids) {
            String jsonStr = WebService.request(UPDATE_JSON, WebService.GET);
            try {
                JSONObject json = new JSONObject(jsonStr);
                JSONObject magisk = json.getJSONObject("magisk");
                Global.Info.remoteMagiskVersion = magisk.getDouble("versionCode");
                Global.Info.magiskLink = magisk.getString("link");
                Global.Info.releaseNoteLink = magisk.getString("note");
            } catch (JSONException ignored) {}
            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            Global.Events.updateCheckDone.trigger();
        }
    }

    public static void checkSafetyNet(Context context) {
        new SafetyNetHelper(context) {
            @Override
            public void handleResults(int i) {
                Global.Info.SNCheckResult = i;
                Global.Events.safetyNetDone.trigger();
            }
        }.requestTest();
    }

    public static class LoadModules extends RootTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... voids) {
            ModuleHelper.createModuleMap();
            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            Global.Events.moduleLoadDone.trigger();
        }
    }

    public static class LoadRepos extends NormalTask<Void, Void, Void> {

        private Context mContext;

        public LoadRepos(Context context) {
            mContext = context;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            ModuleHelper.createRepoMap(mContext);
            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            Global.Events.repoLoadDone.trigger();
        }
    }

    public static class LoadApps extends RootTask<Void, Void, Void> {

        private PackageManager pm;

        public LoadApps(PackageManager packageManager) {
           pm = packageManager;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            Global.Data.appList = pm.getInstalledApplications(0);
            for (Iterator<ApplicationInfo> i = Global.Data.appList.iterator(); i.hasNext(); ) {
                ApplicationInfo info = i.next();
                if (ApplicationAdapter.BLACKLIST.contains(info.packageName) || !info.enabled)
                    i.remove();
            }
            Collections.sort(Global.Data.appList, (a, b) -> a.loadLabel(pm).toString().toLowerCase()
                    .compareTo(b.loadLabel(pm).toString().toLowerCase()));
            Global.Data.magiskHideList = Shell.su(Async.MAGISK_HIDE_PATH + "list");
            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            Global.Events.packageLoadDone.trigger();
        }
    }

    public static class FlashZIP extends RootTask<Void, String, Integer> {

        protected Uri mUri;
        protected File mCachedFile;
        private String mFilename;
        protected ProgressDialog progress;
        private Context mContext;

        public FlashZIP(Context context, Uri uri, String filename) {
            mContext = context;
            mUri = uri;
            mFilename = filename;
        }

        public FlashZIP(Context context, Uri uri) {
            mContext = context;
            mUri = uri;

            // Try to get the filename ourselves
            Cursor c = mContext.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                c.moveToFirst();
                if (nameIndex != -1) {
                    mFilename = c.getString(nameIndex);
                }
                c.close();
            }
            if (mFilename == null) {
                int idx = uri.getPath().lastIndexOf('/');
                mFilename = uri.getPath().substring(idx + 1);
            }
        }

        protected void preProcessing() throws Throwable {}

        protected void copyToCache() throws Throwable {
            publishProgress(mContext.getString(R.string.copying_msg));
            mCachedFile = new File(mContext.getCacheDir().getAbsolutePath() + "/install.zip");
            if (mCachedFile.exists() && !mCachedFile.delete()) {
                Logger.error("FlashZip: Error while deleting already existing file");
                throw new IOException();
            }
            try (
                    InputStream in = mContext.getContentResolver().openInputStream(mUri);
                    OutputStream outputStream = new FileOutputStream(mCachedFile)
            ) {
                byte buffer[] = new byte[1024];
                int length;
                if (in == null) throw new FileNotFoundException();
                while ((length = in.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
                Logger.dev("FlashZip: File created successfully - " + mCachedFile.getPath());
            } catch (FileNotFoundException e) {
                Logger.error("FlashZip: Invalid Uri");
                throw e;
            } catch (IOException e) {
                Logger.error("FlashZip: Error in creating file");
                throw e;
            }
        }

        protected boolean unzipAndCheck() {
            ZipUtils.unzip(mCachedFile, mCachedFile.getParentFile(), "META-INF/com/google/android");
            List<String> ret;
            ret = Utils.readFile(mCachedFile.getParent() + "/META-INF/com/google/android/updater-script");
            return Utils.isValidShellResponse(ret) && ret.get(0).contains("#MAGISK");
        }

        @Override
        protected void onPreExecute() {
            progress = new ProgressDialog(mContext);
            progress.setTitle(R.string.zip_install_progress_title);
            progress.show();
        }

        @Override
        protected void onProgressUpdate(String... values) {
            progress.setMessage(values[0]);
        }

        @Override
        protected Integer doInBackground(Void... voids) {
            Logger.dev("FlashZip Running... " + mFilename);
            List<String> ret;
            try {
                preProcessing();
                copyToCache();
            } catch (Throwable e) {
                e.printStackTrace();
                return -1;
            }
            if (!unzipAndCheck()) return 0;
            publishProgress(mContext.getString(R.string.zip_install_progress_msg, mFilename));
            ret = Shell.su(
                    "BOOTMODE=true sh " + mCachedFile.getParent() +
                            "/META-INF/com/google/android/update-binary dummy 1 " + mCachedFile.getPath(),
                    "if [ $? -eq 0 ]; then echo true; else echo false; fi"
            );
            if (!Utils.isValidShellResponse(ret)) return -1;
            Logger.dev("FlashZip: Console log:");
            for (String line : ret) {
                Logger.dev(line);
            }
            Shell.su(
                    "rm -rf " + mCachedFile.getParent() + "/*",
                    "rm -rf " + TMP_FOLDER_PATH
            );
            if (Boolean.parseBoolean(ret.get(ret.size() - 1))) {
                return 1;
            }
            return -1;
        }

        // -1 = error, manual install; 0 = invalid zip; 1 = success
        @Override
        protected void onPostExecute(Integer result) {
            super.onPostExecute(result);
            progress.dismiss();
            switch (result) {
                case -1:
                    Toast.makeText(mContext, mContext.getString(R.string.install_error), Toast.LENGTH_LONG).show();
                    Toast.makeText(mContext, mContext.getString(R.string.manual_install_1, mUri.getPath()), Toast.LENGTH_LONG).show();
                    Toast.makeText(mContext, mContext.getString(R.string.manual_install_2), Toast.LENGTH_LONG).show();
                    break;
                case 0:
                    Toast.makeText(mContext, mContext.getString(R.string.invalid_zip), Toast.LENGTH_LONG).show();
                    break;
                case 1:
                    onSuccess();
                    break;
            }
        }

        protected void onSuccess() {
            Global.Events.updateCheckDone.trigger();
            new LoadModules().exec();

            Utils.getAlertDialogBuilder(mContext)
                    .setTitle(R.string.reboot_title)
                    .setMessage(R.string.reboot_msg)
                    .setPositiveButton(R.string.reboot, (dialogInterface, i) -> Shell.sh("su -c reboot"))
                    .setNegativeButton(R.string.no_thanks, null)
                    .show();
        }
    }

    public static class MagiskHide extends RootTask<Object, Void, Void> {
        @Override
        protected Void doInBackground(Object... params) {
            String command = (String) params[0];
            Shell.su(MAGISK_HIDE_PATH + command);
            return null;
        }

        public void add(CharSequence packageName) {
            exec("add " + packageName);
        }

        public void rm(CharSequence packageName) {
            exec("rm " + packageName);
        }

        public void enable() {
            exec("enable");
        }

        public void disable() {
            exec("disable");
        }

    }

    public static class GetBootBlocks extends RootTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            if (Shell.rootAccess()) {
                Global.Data.blockList = Shell.su("ls /dev/block | grep mmc");
                if (Global.Info.bootBlock == null)
                    Global.Info.bootBlock = Utils.detectBootImage();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            Global.Events.blockDetectionDone.trigger();
        }
    }
}