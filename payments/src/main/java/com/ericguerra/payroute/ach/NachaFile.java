package com.ericguerra.payroute.ach;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a NACHA (ACH) file of PPD/CCD debit entries for the originating bank's SFTP drop.
 * Every record is exactly 94 characters; blocks of 10 records are padded with '9' lines.
 */
public final class NachaFile {
    public record Entry(String routingNumber, String accountNumber, boolean checking, long amountCents,
                        String individualId, String individualName, String traceSuffix) {}

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");

    private final String immediateDestination; // bank routing (9)
    private final String immediateOrigin;      // company id (10)
    private final String destinationName;
    private final String originName;
    private final String companyName;
    private final String companyId;            // 10 chars, often "1" + EIN
    private final String odfiRouting8;         // first 8 digits of originating bank routing

    public NachaFile(String immediateDestination, String immediateOrigin, String destinationName, String originName,
                     String companyName, String companyId, String odfiRouting8) {
        this.immediateDestination = immediateDestination;
        this.immediateOrigin = immediateOrigin;
        this.destinationName = destinationName;
        this.originName = originName;
        this.companyName = companyName;
        this.companyId = companyId;
        this.odfiRouting8 = odfiRouting8;
    }

    /** ABA checksum: 3·d1 + 7·d2 + 1·d3 + … ≡ 0 (mod 10). Rejects typos before the bank does. */
    public static boolean validRouting(String r) {
        if (r == null || !r.matches("\\d{9}")) return false;
        int[] w = {3, 7, 1, 3, 7, 1, 3, 7, 1};
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += w[i] * (r.charAt(i) - '0');
        return sum % 10 == 0;
    }

    public String build(List<Entry> entries, String secCode, String description, LocalDate effective, LocalDate created, LocalTime time, char fileIdModifier) {
        if (!secCode.equals("PPD") && !secCode.equals("CCD")) throw new IllegalArgumentException("SEC code must be PPD or CCD");
        for (Entry e : entries) {
            if (!validRouting(e.routingNumber())) throw new IllegalArgumentException("invalid routing number " + e.routingNumber());
            if (e.amountCents() <= 0 || e.amountCents() > 99_999_999_99L) throw new IllegalArgumentException("amount out of range");
        }
        List<String> rec = new ArrayList<>();
        // 1 File Header
        rec.add("1" + "01" + " " + immediateDestination + pad(immediateOrigin, 10) + created.format(YYMMDD) + time.format(HHMM)
                + fileIdModifier + "094" + "10" + "1" + pad(destinationName, 23) + pad(originName, 23) + pad("", 8));
        // 5 Batch Header (225 = debits only)
        rec.add("5" + "225" + pad(companyName, 16) + pad("", 20) + pad(companyId, 10) + secCode + pad(description, 10)
                + pad("", 6) + effective.format(YYMMDD) + "   " + "1" + odfiRouting8 + num(1, 7));
        long total = 0, hash = 0;
        int seq = 0;
        for (Entry e : entries) {
            seq++;
            String txCode = e.checking() ? "27" : "37"; // debit checking / savings
            rec.add("6" + txCode + e.routingNumber().substring(0, 8) + e.routingNumber().charAt(8) + pad(e.accountNumber(), 17)
                    + num(e.amountCents(), 10) + pad(e.individualId(), 15) + pad(e.individualName(), 22) + "  " + "0"
                    + odfiRouting8 + num(seq, 7));
            total += e.amountCents();
            hash += Long.parseLong(e.routingNumber().substring(0, 8));
        }
        String entryHash = num(hash % 10_000_000_000L, 10);
        // 8 Batch Control
        rec.add("8" + "225" + num(entries.size(), 6) + entryHash + num(total, 12) + num(0, 12) + pad(companyId, 10)
                + pad("", 19) + pad("", 6) + odfiRouting8 + num(1, 7));
        int blockCount = (int) Math.ceil((rec.size() + 1) / 10.0);
        // 9 File Control
        rec.add("9" + num(1, 6) + num(blockCount, 6) + num(entries.size(), 8) + entryHash + num(total, 12) + num(0, 12) + pad("", 39));
        while (rec.size() % 10 != 0) rec.add("9".repeat(94));
        for (String r : rec) if (r.length() != 94) throw new IllegalStateException("record length " + r.length() + ": " + r);
        return String.join("\n", rec) + "\n";
    }

    private static String pad(String s, int n) {
        String v = s == null ? "" : s.toUpperCase().replaceAll("[^A-Z0-9 &.,/-]", "");
        return v.length() >= n ? v.substring(0, n) : v + " ".repeat(n - v.length());
    }

    private static String num(long v, int n) {
        String s = Long.toString(v);
        if (s.length() > n) throw new IllegalArgumentException(v + " does not fit in " + n + " digits");
        return "0".repeat(n - s.length()) + s;
    }
}
