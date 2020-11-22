/*******************************************************************************
 * Copyright (c) 2020. Bytedance Inc.
 *
 * This source code is licensed under the MIT license found in the LICENSE file in the root directory of this source tree.
 ******************************************************************************/

package com.tiktok.appevents;

import com.tiktok.BuildConfig;
import com.tiktok.TikTokBusinessSdk;
import com.tiktok.util.HttpRequestUtil;
import com.tiktok.util.TTLogger;
import com.tiktok.util.TTUtil;
import com.tiktok.util.TimeUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

class TTRequest {
    private static final String TAG = TTRequest.class.getCanonicalName();
    private static final TTLogger logger = new TTLogger(TAG, TikTokBusinessSdk.getLogLevel());

    private static final int MAX_EVENT_SIZE = 50;

    // stats for the current batch
    private static int toBeSentRequests = 0;
    private static int failedRequests = 0;
    private static int successfulRequests = 0;

    // stats for the whole lifecycle
    private static final TreeSet<Long> allRequestIds = new TreeSet<>();
    private static final List<TTAppEvent> successfullySentRequests = new ArrayList<>();

    private static final Map<String, String> headParamMap = new HashMap<>();

    static {
        // these fields wont change, so cache it locally to enhance performance
        headParamMap.put("Content-Type", "application/json");
        headParamMap.put("Connection", "Keep-Alive");
        String ua = String.format("tiktok-business-android-sdk/%s/%s",
                BuildConfig.VERSION_NAME,
                TikTokBusinessSdk.getApiAvailableVersion());
        headParamMap.put("User-Agent", ua);
    }

    public static JSONObject getBusinessSDKConfig() {
        logger.info("Try to fetch global configs");
        headParamMap.put("access-token", TikTokBusinessSdk.getAccessToken());
        String url = "https://ads.tiktok.com/open_api/business_sdk_config/get/?app_id=" + TikTokBusinessSdk.getAppId();
        String result = HttpRequestUtil.doGet(url, headParamMap);
        logger.debug(result);
        JSONObject config = null;
        if (result != null) {
            try {
                JSONObject resultJson = new JSONObject(result);
                Integer code = (Integer) resultJson.get("code");
                if (code == 0) {
                    config = (JSONObject) resultJson.get("data");
                }
                logger.info("Global config fetched: " + TTUtil.ppStr(config));
            } catch (Exception e) {
                // might be api returning something wrong
                TTCrashHandler.handleCrash(TAG, e);
            }
        }

        // might be api returning something wrong
        return config;
    }

    // for debugging purpose
    public static synchronized List<TTAppEvent> getSuccessfullySentRequests() {
        return successfullySentRequests;
    }

    /**
     * Try to send events to api with MTU set to 1000 app events,
     * If there are more than 1000 events, they will be split into several chunks and
     * then be sent separately,
     * Any failed events will be accumulated and finally returned.
     *
     * @param appEventList
     * @return the accumulation of all failed events
     */
    public static synchronized List<TTAppEvent> reportAppEvent(JSONObject basePayload, List<TTAppEvent> appEventList) {
        TTUtil.checkThread(TAG);
        // access-token might change during runtime
        headParamMap.put("access-token", TikTokBusinessSdk.getAccessToken());

        if (appEventList == null || appEventList.size() == 0) {
            return new ArrayList<>();
        }

        toBeSentRequests = appEventList.size();
        for (TTAppEvent event : appEventList) {
            allRequestIds.add(event.getUniqueId());
        }
        failedRequests = 0;
        successfulRequests = 0;
        notifyChange();

        String url = "https://ads.tiktok.com/open_api/" + TikTokBusinessSdk.getApiAvailableVersion() + "/app/batch/";

        List<TTAppEvent> failedEvents = new ArrayList<>();

        List<List<TTAppEvent>> chunks = averageAssign(appEventList, MAX_EVENT_SIZE);

        for (List<TTAppEvent> currentBatch : chunks) {
            List<JSONObject> batch = new ArrayList<>();
            for (TTAppEvent event : currentBatch) {
                JSONObject propertiesJson = transferJson(event);
                if (propertiesJson == null) {
                    continue;
                }
                batch.add(propertiesJson);
            }

            JSONObject bodyJson = basePayload;
            try {
                bodyJson.put("batch", new JSONArray(batch));
            } catch (Exception e) {
                failedEvents.addAll(currentBatch);
                TTCrashHandler.handleCrash(TAG, e);
                continue;
            }

            try {
                String bodyStr = bodyJson.toString(4);
                logger.debug("To Api:\n" + bodyStr);
            } catch (JSONException e) {
            }

            String result = HttpRequestUtil.doPost(url, headParamMap, bodyJson.toString());

            if (result == null) {
                failedEvents.addAll(currentBatch);
                failedRequests += currentBatch.size();
            } else {
                try {
                    JSONObject resultJson = new JSONObject(result);
                    Integer code = (Integer) resultJson.get("code");

                    if (code != 0) {
                        failedEvents.addAll(currentBatch);
                        failedRequests += currentBatch.size();
                    } else {
                        successfulRequests += currentBatch.size();
                        successfullySentRequests.addAll(currentBatch);
                    }
                } catch (JSONException e) {
                    failedRequests += currentBatch.size();
                    failedEvents.addAll(currentBatch);
                    TTCrashHandler.handleCrash(TAG, e);
                }
                logger.debug(TTUtil.ppStr(result));
            }
            notifyChange();
        }
        logger.debug("Flushed %d events, failed to flush %d events", successfulRequests, failedEvents.size());
        toBeSentRequests = 0;
        failedRequests = 0;
        successfulRequests = 0;
        notifyChange();
        return failedEvents;
    }

    private static void notifyChange() {
        if (TikTokBusinessSdk.networkListener != null) {
            TikTokBusinessSdk.networkListener.onNetworkChange(toBeSentRequests, successfulRequests,
                    failedRequests, allRequestIds.size() + TTAppEventsQueue.size(), successfullySentRequests.size());
        }
    }

    private static JSONObject transferJson(TTAppEvent event) {
        if (event == null) {
            return null;
        }
        try {
            JSONObject propertiesJson = new JSONObject();
            propertiesJson.put("type", "track");
            propertiesJson.put("event", event.getEventName());
            propertiesJson.put("timestamp", TimeUtil.getISO8601Timestamp(event.getTimeStamp()));
            JSONObject properties = new JSONObject(event.getPropertiesJson());
            if (properties.length() != 0) {
                propertiesJson.put("properties", properties);
            }
            propertiesJson.put("context", TTRequestBuilder.getContextForApi());
            return propertiesJson;
        } catch (JSONException e) {
            TTCrashHandler.handleCrash(TAG, e);
            return null;
        }
    }

    /**
     * split event list
     *
     * @param sourceList
     * @param splitNum
     * @param <T>
     */
    public static <T> List<List<T>> averageAssign(List<T> sourceList, int splitNum) {
        List<List<T>> result = new ArrayList<>();

        int size = sourceList.size();
        int times = size % splitNum == 0 ? size / splitNum : size / splitNum + 1;
        for (int i = 0; i < times; i++) {
            int start = i * splitNum;
            int end = i * splitNum + splitNum;
            result.add(new ArrayList<>(sourceList.subList(start, Math.min(size, end))));
        }
        return result;
    }
}