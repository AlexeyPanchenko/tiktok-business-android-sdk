package com.tiktok.util;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class TimeUtil {
    private static DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault());

    /**
     * return ISO8601 time format
     *
     * @param date
     * @return
     */
    public static String getISO8601Timestamp(Date date) {
//        TimeZone tz = TimeZone.getTimeZone("Asia/Shanghai");
//        df.setTimeZone(tz);
        return df.format(date);
    }

    private final static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

    public static String dateStr(int dayDifference) {
        // now
        Calendar c1 = Calendar.getInstance();
        if (dayDifference != 0) {
            c1.add(Calendar.DATE, dayDifference);
        }
        return sdf.format(c1.getTime());
    }

    public static boolean isNowAfter(String referenceStr, int days) {
        String yesterdayStr = dateStr(-days);
        return yesterdayStr.equals(referenceStr);
    }
}
