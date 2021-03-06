/**
 * Copyright (C) 2014 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app.context;

import android.content.Context;
import android.database.sqlite.SQLiteException;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import net.jcip.annotations.ThreadSafe;

import org.andstatus.app.ClassInApplicationPackage;
import org.andstatus.app.account.PersistentAccounts;
import org.andstatus.app.data.AssersionData;
import org.andstatus.app.data.MyDatabase;
import org.andstatus.app.data.MyDatabaseConverterController;
import org.andstatus.app.net.HttpConnection;
import org.andstatus.app.origin.PersistentOrigins;
import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.RelativeTime;

import java.util.Locale;

/**
 * Contains global state of the application
 * The objects are effectively immutable
 * @author yvolk@yurivolkov.com
 */
@ThreadSafe
public final class MyContextImpl implements MyContext {
    private static final String TAG = MyContextImpl.class.getSimpleName();

    private MyContextState mState = MyContextState.EMPTY;
    /**
     * Single context object for which we will request SharedPreferences
     */
    private Context mContext = null;
    /**
     * Name of the object that initialized the class
     */
    private String mInitializedBy;
    /**
     * When preferences, loaded into this class, were changed
     */
    private long mPreferencesChangeTime = 0;
    private MyDatabase mDb;
    private PersistentAccounts mPersistentAccounts;
    private PersistentOrigins mPersistentOrigins;
    
    private volatile boolean mExpired = false;

    private final Locale mLocale = Locale.getDefault();
    
    private static volatile boolean mInForeground = false;
    private static volatile long mInForegroundChangedAt = 0;
    private static final long CONSIDER_IN_BACKGROUND_AFTER_SECONDS = 20;
    
    private MyContextImpl() {
    }

    @Override
    public MyContext newInitialized(Context context, String initializerName) {
        final String method = "newInitialized";
        MyContextImpl newMyContext = getCreator(context, initializerName);
        if ( newMyContext.mContext != null) {
            MyLog.v(TAG, method + " Starting initialization by " + initializerName);
            newMyContext.mPreferencesChangeTime = MyPreferences.getPreferencesChangeTime();
            MyDatabase newDb = new MyDatabase(newMyContext.mContext);
            try {
                newMyContext.mState = newDb.checkState();
                switch (newMyContext.mState) {
                    case READY:
                            newMyContext.mDb = newDb;
                            newMyContext.mPersistentOrigins.initialize(newMyContext);
                            newMyContext.mPersistentAccounts.initialize(newMyContext);
                        break;
                    default: 
                        break;
                }
            } catch (SQLiteException e) {
                MyLog.e(TAG, method + " Error", e);
                newMyContext.mState = MyContextState.ERROR;
                newDb.close();
                newMyContext.mDb = null;
            }
        }

        MyLog.v(this, toString());
        return newMyContext;
    }
    
    @Override
    public String toString() {
        return  MyLog.objTagToString(this) +  " initialized by " + mInitializedBy + "; state=" + mState +  "; " + (mContext == null ? "no context" : "context=" + mContext.getClass().getName());
    }

    @Override
    public MyContext newCreator(Context context, String initializerName) {
        MyContextImpl newMyContext = getCreator(context, initializerName);
        MyLog.v(this, "newCreator by " + newMyContext.mInitializedBy 
                + (newMyContext.mContext == null ? "" : " context: " + newMyContext.mContext.getClass().getName()));
        return newMyContext;
    }

    private MyContextImpl getCreator(Context context, String initializerName) {
        MyContextImpl newMyContext = getEmpty();
        newMyContext.mInitializedBy = initializerName;
        if (context != null) {
            Context contextToUse = context.getApplicationContext();
        
            if ( contextToUse == null) {
                MyLog.w(TAG, "getApplicationContext is null, trying the context itself: " + context.getClass().getName());
                contextToUse = context;
            }
            // TODO: Maybe we need to determine if the context is compatible, using some Interface...
            // ...but we don't have any yet.
            if (!context.getClass().getName().contains(ClassInApplicationPackage.PACKAGE_NAME)) {
                MyLog.w(TAG, "Incompatible context: " + contextToUse.getClass().getName());
                contextToUse = null;
            }
            newMyContext.mContext = contextToUse;
        }
        return newMyContext;
    }
    
    public static MyContextImpl getEmpty() {
        MyContextImpl myContext = new MyContextImpl();
        myContext.mPersistentAccounts = PersistentAccounts.getEmpty();
        myContext.mPersistentOrigins = PersistentOrigins.getEmpty();
        return myContext;
    }

    @Override
    public boolean initialized() {
        return mState != MyContextState.EMPTY;
    }

    @Override
    public boolean isReady() {
        return mState == MyContextState.READY && !MyDatabaseConverterController.isUpgrading();
    }

    @Override
    public MyContextState state() {
        return mState;
    }
    
    @Override
    public Context context() {
        return mContext;
    }

    @Override
    public String initializedBy() {
        return mInitializedBy;
    }
    
    @Override
    public long preferencesChangeTime() {
        return mPreferencesChangeTime;
    }
    
    @Override
    public MyDatabase getDatabase() {
        return mDb;
    }

    @Override
    /**
     * 2013-12-09 After getting the error "java.lang.IllegalStateException: attempt to re-open an already-closed object: SQLiteDatabase"
     * and reading Inet, I decided NOT to db.close here.
     */
    public void release() {
        MyLog.forget();
    }

    @Override
    public PersistentAccounts persistentAccounts() {
        return mPersistentAccounts;
    }

    @Override
    public boolean isTestRun() {
        return false;
    }

    @Override
    public void put(AssersionData data) {
        // Noop for this implementation
    }

    @Override
    public boolean isExpired() {
        return mExpired;
    }

    @Override
    public void setExpired() {
        mExpired = true;
    }

    @Override
    public Locale getLocale() {
        return mLocale;
    }

    @Override
    public PersistentOrigins persistentOrigins() {
        return mPersistentOrigins;
    }

    @Override
    public HttpConnection getHttpConnectionMock() {
        return null;
    }

    @Override
    public boolean isOnline() {
        if (isOnlineNotLogged()) {
            return true;
        } else {
            MyLog.v(this, "Internet Connection Not Present");
            return false;
        }
    }

    /**
     * Based on http://stackoverflow.com/questions/1560788/how-to-check-internet-access-on-android-inetaddress-never-timeouts
     */
    private boolean isOnlineNotLogged() {
        boolean is = false;
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        NetworkInfo networkInfo = cm.getActiveNetworkInfo();
        if (networkInfo == null) {
            return false;
        }
        is = networkInfo.isAvailable() && networkInfo.isConnected();
        return is;
    }

    @Override
    public boolean isInForeground() {
        if (!mInForeground
                && !RelativeTime.moreSecondsAgoThan(mInForegroundChangedAt,
                        CONSIDER_IN_BACKGROUND_AFTER_SECONDS)) {
            return true;
        }
        return mInForeground;
    }

    @Override
    public void setInForeground(boolean inForeground) {
        setInForegroundStatic(inForeground);
    }
    
    /** To avoid "Write to static field" warning  
     *  On static members in interfaces: http://stackoverflow.com/questions/512877/why-cant-i-define-a-static-method-in-a-java-interface
     * */
    private static void setInForegroundStatic(boolean inForeground) {
        if (mInForeground != inForeground) {
            mInForegroundChangedAt = System.currentTimeMillis();
        }
        mInForeground = inForeground;
    }
}
