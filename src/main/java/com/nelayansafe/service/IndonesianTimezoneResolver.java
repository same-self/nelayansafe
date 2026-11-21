package com.nelayansafe.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class IndonesianTimezoneResolver {
    public record TimezoneInfo(ZoneId zoneId, String abbreviation, String utcOffsetLabel) {
        public String displayLabel() {
            return abbreviation + " (" + utcOffsetLabel + ")";
        }
    }

    public static final TimezoneInfo WIB = new TimezoneInfo(ZoneId.of("Asia/Jakarta"), "WIB", "UTC+7");
    public static final TimezoneInfo WITA = new TimezoneInfo(ZoneId.of("Asia/Makassar"), "WITA", "UTC+8");
    public static final TimezoneInfo WIT = new TimezoneInfo(ZoneId.of("Asia/Jayapura"), "WIT", "UTC+9");

    private static final DateTimeFormatter SHORT_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FULL_DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");
    private static final DateTimeFormatter LOG_DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss");

    private IndonesianTimezoneResolver() {}

    public static TimezoneInfo resolve(String province) {
        return resolve(province, null);
    }

    public static TimezoneInfo resolve(String province, String areaName) {
        TimezoneInfo fromProvince = matchProvince(province);
        if (fromProvince != null) {
            return fromProvince;
        }
        TimezoneInfo fromArea = matchAreaFallback(areaName);
        return fromArea != null ? fromArea : WIB;
    }

    private static TimezoneInfo matchProvince(String province) {
        if (province == null || province.isBlank()) {
            return null;
        }
        String norm = province.toLowerCase(Locale.ROOT).trim();
        if (norm.equals("indonesia")) {
            return null;
        }

        // WIT (UTC+9): Maluku, Maluku Utara, and all Papua provinces
        if (norm.contains("papua") || norm.contains("maluku")) {
            return WIT;
        }

        // WITA (UTC+8): Bali, NTB, NTT, Sulawesi, Gorontalo, South/East/North Kalimantan
        if (norm.contains("sulawesi")
                || norm.contains("gorontalo")
                || norm.equals("bali")
                || norm.contains("provinsi bali")
                || norm.contains("nusa tenggara")
                || norm.equals("ntb")
                || norm.equals("ntt")
                || norm.contains("kalimantan selatan")
                || norm.contains("kalimantan timur")
                || norm.contains("kalimantan utara")) {
            return WITA;
        }

        // WIB (UTC+7): Sumatra, Java, Bangka Belitung, Kepri, West/Central Kalimantan
        if (norm.contains("aceh")
                || norm.contains("sumatera")
                || norm.contains("sumatra")
                || norm.contains("riau")
                || norm.contains("jambi")
                || norm.contains("bengkulu")
                || norm.contains("lampung")
                || norm.contains("bangka")
                || norm.contains("belitung")
                || norm.contains("jakarta")
                || norm.contains("banten")
                || norm.contains("jawa")
                || norm.contains("yogyakarta")
                || norm.contains("kalimantan barat")
                || norm.contains("kalimantan tengah")) {
            return WIB;
        }

        return null;
    }

    private static TimezoneInfo matchAreaFallback(String areaName) {
        if (areaName == null || areaName.isBlank()) {
            return null;
        }
        String norm = areaName.toLowerCase(Locale.ROOT).trim();

        // WIT area hints
        if (norm.contains("papua")
                || norm.contains("maluku")
                || norm.contains("halmahera")
                || norm.contains("ambon")
                || norm.contains("laut banda")
                || norm.contains("seram")
                || norm.contains("jayapura")
                || norm.contains("merauke")
                || norm.contains("sorong")
                || norm.contains("biak")
                || norm.contains("fakfak")
                || norm.contains("kaimana")
                || norm.contains("raja ampat")
                || norm.contains("kepulauan aru")
                || norm.contains("laut aru")
                || norm.contains("arafuru")
                || norm.contains("tanimbar")) {
            return WIT;
        }

        // WITA area hints
        if (norm.contains("sulawesi")
                || norm.contains("gorontalo")
                || norm.contains("makassar")
                || norm.contains("sinjai")
                || norm.contains("manado")
                || norm.contains("kendari")
                || norm.contains("teluk palu")
                || norm.contains("perairan palu")
                || norm.contains("selat bali")
                || norm.contains("perairan bali")
                || norm.contains("laut bali")
                || norm.contains("lombok")
                || norm.contains("sumbawa")
                || norm.contains("flores")
                || norm.contains("kupang")
                || norm.contains("pulau sumba")
                || norm.contains("selat sumba")
                || norm.contains("laut sawu")
                || norm.contains("timor")
                || norm.contains("balikpapan")
                || norm.contains("banjarmasin")
                || norm.contains("kotabaru")
                || norm.contains("tarakan")
                || norm.contains("samarinda")) {
            return WITA;
        }

        return null;
    }

    public static String formatShort(ZonedDateTime utcTime, TimezoneInfo tz) {
        return utcTime.withZoneSameInstant(tz.zoneId()).format(SHORT_TIME_FMT) + " " + tz.abbreviation();
    }

    public static String formatFullNow(TimezoneInfo tz) {
        return ZonedDateTime.now(tz.zoneId()).format(FULL_DATE_TIME_FMT) + " " + tz.abbreviation();
    }

    public static String formatLogNow(TimezoneInfo tz) {
        return ZonedDateTime.now(tz.zoneId()).format(LOG_DATE_TIME_FMT) + " " + tz.abbreviation();
    }
}
