package com.acn.behavior.web;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import com.acn.behavior.db.ACNSp;
import com.acn.behavior.db.BehaviorDBManager;
import com.acn.behavior.model.BehaviorModel;
import com.acn.behavior.util.CommonType;
import com.acn.behavior.util.Constants;
import com.acn.behavior.util.ProcessUtil;
import com.acn.behavior.util.SDKLogger;
import com.acn.biz.model.BaseInfo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class Client {

    public static final String ACTION_RETRY = "com.acn.action.retry";

    private Context context;
    private Repo repo;
    private Handler handler;
    private ExecutorService eventExecutorService;
    private BehaviorDBManager dbManager;

    //    private ScheduledExecutorService sendDBService;
    private ScheduledFuture<?> scheduledFuture;

    public Client(Context context) {
        this.context = context.getApplicationContext();
        handler = new Handler(Looper.getMainLooper());
        eventExecutorService = Executors.newFixedThreadPool(5);

        repo = new Repo();
        dbManager = new BehaviorDBManager(context);
        SDKLogger.d("create schedule thread");
        scheduledFuture = Executors.newScheduledThreadPool(1).scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    List<BehaviorModel> models = dbManager.getAllASCTimestamp(BaseInfo.getInstance().getUserId());
                    if (models != null) {
                        SDKLogger.d("behavior count in db is " + models.size());
                    }

                    if (models != null && models.size() > 0) {
                        //先从最老的开始
                        BehaviorModel m = models.get(0);

                        if (m.behaviorType == CommonType.OPEN_DAPP) {
                            if (!isNeedUploadOpenBehavior()) {
                                dbManager.delete(m.timestamp);
                                SDKLogger.d("has login, delete it. " + m.timestamp);
                                return;
                            }
                        }

                        if (!TextUtils.isEmpty(m.hash) && EthClient.isTransactionSuccess(BaseInfo.getInstance().getSideChainRPCUrl(), m.hash)) {
                            dbManager.delete(m.timestamp);
                            SDKLogger.d("has written in block chain, delete. " + m.timestamp);
                        } else {
                            repo.onEvent(m.behaviorType, m.extra, Long.valueOf(m.timestamp));
                            SDKLogger.d("write to block chain. " + m.timestamp);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 0, 30, TimeUnit.SECONDS);
        if (ProcessUtil.isMainProcess(context)) {
            registerReceiver();
        } else {
            SDKLogger.e("is not main process, don't register receiver!");
        }
    }

    public Context getContext() {
        return context;
    }

    public ExecutorService getEventExecutorService() {
        return eventExecutorService;
    }


    public Repo getRepo() {
        return repo;
    }

    public BehaviorDBManager getDbManager() {
        return dbManager;
    }

    public ScheduledFuture<?> getScheduledFuture() {
        return scheduledFuture;
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(ACTION_RETRY);
        getContext().registerReceiver(new ConnectivityChangeReceiver(), filter);
    }


    public void retry() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                context.sendBroadcast(new Intent(ACTION_RETRY));
            }
        }, 10 * 1000);
    }

    private class ConnectivityChangeReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = cm.getActiveNetworkInfo();
            boolean connected = networkInfo != null && networkInfo.isConnected();
            if (connected) {
                SDKLogger.d("network connected");
            }
        }
    }

    private boolean isNeedUploadOpenBehavior() {
        long lastOpenDay = ACNSp.getLastOpenTimestamp() / Constants.ONE_DAY_MILLISECOND;
        long currentDay = System.currentTimeMillis() / Constants.ONE_DAY_MILLISECOND;
        return currentDay > lastOpenDay;
    }

}
