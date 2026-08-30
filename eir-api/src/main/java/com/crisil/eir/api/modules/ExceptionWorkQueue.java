package com.crisil.eir.api.modules;

import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.exception.ExceptionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The exception queue of {@code docs/04-data-model.md} 04 § 3, addressed by an id an HTTP caller
 * can hold — which is the one thing {@link ExceptionRecord} does not give it.
 *
 * <p><b>Why an id has to be invented here.</b> 04 § 3's {@code EXCEPTION} entity has a primary key
 * in the database and {@link ExceptionRecord} has none: it is the eight columns and nothing else,
 * on purpose, because a record is immutable and working it produces a new one. 06 § 6 nonetheless
 * addresses a queue entry as {@code /exceptions/{id}}, so this class defines the id and defines it
 * as the pair the rest of the close machinery already matches on —
 * {@code contractId + ":" + category} — because that is exactly what
 * {@link com.crisil.eir.policy.close.ExceptionAcceptance#covers} compares. An id at any coarser
 * grain would let one signature clear two exceptions that need two decisions; an id at a finer
 * grain (a queue position, say) would address rows the acceptance artefact cannot tell apart, so a
 * caller could accept row 3 and have the gate read it as authority over row 4.
 *
 * <p><b>The consequence, stated rather than hidden.</b> The queue does not de-duplicate — one
 * contract can raise an unmapped fee code twice — so two rows can share an id. That is not
 * resolved by picking the first: {@link #find} refuses an ambiguous id and names both rows, because
 * silently working one of two identical-looking exceptions is how a queue entry gets signed for by
 * somebody who was looking at the other one.
 *
 * <p>Every mutator returns the worked record and leaves the underlying {@link ExceptionQueue} to do
 * the replacement, so the queue keeps 04 § 3's one-row-per-exception shape rather than growing a
 * history this API would then have to explain.
 */
public final class ExceptionWorkQueue {

    /**
     * Between the contract id and the category in an exception id.
     *
     * <p>A colon rather than a slash, so the id is one path segment and one form value: a slash
     * would make {@code /api/exceptions/C-0003/MISSING_MANDATORY_FIELD/accept} four segments and
     * the id would stop being a token a caller can copy out of the listing and paste back.
     */
    public static final String ID_SEPARATOR = ":";

    private final ExceptionQueue queue;

    private ExceptionWorkQueue(ExceptionQueue queue) {
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    /** The work queue over an existing {@link ExceptionQueue}. */
    public static ExceptionWorkQueue over(ExceptionQueue queue) {
        return new ExceptionWorkQueue(queue);
    }

    /** The id 06 § 6 addresses this row by. See the class javadoc for why it is this pair. */
    public static String idOf(ExceptionRecord record) {
        Objects.requireNonNull(record, "record");
        return record.contractId() + ID_SEPARATOR + record.category().name();
    }

    /** The queue underneath, for the gate summaries a listing reports. */
    public ExceptionQueue queue() {
        return queue;
    }

    /** How many rows the queue holds, worked or not. */
    public int size() {
        return queue.size();
    }

    /**
     * The work queue, filtered on status and category — 06 § 6's
     * {@code ?status=OPEN&category=NO_SOLUTION}.
     *
     * <p>Both filters are optional and null means "every one of them". They are applied
     * independently and not collapsed: a caller asking for {@code OPEN} entries in a category that
     * raised nothing gets an empty list rather than the {@code OPEN} entries of every category,
     * which is the failure mode of a filter that ignores what it does not recognise.
     */
    public List<ExceptionRecord> list(ExceptionStatus status, ExceptionCategory category) {
        List<ExceptionRecord> matching = new ArrayList<>();
        for (ExceptionRecord record : queue.records()) {
            if ((status == null || record.status() == status)
                && (category == null || record.category() == category)) {
                matching.add(record);
            }
        }
        return matching;
    }

    /**
     * The one row this id addresses.
     *
     * @throws FormBody.BadRequest if no row carries the id, or if more than one does — a 400 in
     *     both cases, because the caller named something the queue cannot act on, which is the
     *     same class of mistake as naming a contract that is not on the book
     */
    public ExceptionRecord find(String id) {
        Objects.requireNonNull(id, "id");
        String wanted = id.strip();
        int split = wanted.lastIndexOf(ID_SEPARATOR);
        if (split < 0) {
            throw new FormBody.BadRequest("'" + wanted + "' is not an exception id; an id is"
                + " contractId" + ID_SEPARATOR + "CATEGORY, as GET /api/exceptions reports it."
                + " The ids this queue holds are " + ids());
        }
        // The contract half is compared exactly and the category half is case-folded. Not an
        // oversight in either direction: two contract ids differing in case are two contracts —
        // ExceptionAcceptance says so about the same key — while the category is an enum, and the
        // status and category query filters already accept it in any case. Folding the contract id
        // would let a caller work C-0003's exception by typing c-0003.
        String contractId = wanted.substring(0, split);
        String category = wanted.substring(split + ID_SEPARATOR.length());
        List<ExceptionRecord> matching = new ArrayList<>();
        for (ExceptionRecord record : queue.records()) {
            if (record.contractId().equals(contractId)
                && record.category().name().equalsIgnoreCase(category)) {
                matching.add(record);
            }
        }
        if (matching.isEmpty()) {
            throw new FormBody.BadRequest("no queued exception has id '" + wanted + "'; the ids"
                + " this queue holds are " + ids() + ". An id is contractId" + ID_SEPARATOR
                + "CATEGORY, as GET /api/exceptions reports it");
        }
        if (matching.size() > 1) {
            // Reachable: ExceptionQueue.raise does not de-duplicate, so one contract can hold two
            // rows of one category. Answering with the first would have the operator sign a note
            // against whichever of the two the iteration order happened to reach.
            throw new FormBody.BadRequest("id '" + wanted + "' addresses " + matching.size()
                + " queued exceptions and cannot say which: " + describeAll(matching)
                + ". 04 § 3's queue does not de-duplicate, so a contract can raise one category"
                + " twice, and working one of two rows that look identical is how a note gets"
                + " signed against the wrong one");
        }
        return matching.get(0);
    }

    /**
     * Records that the defect behind {@code row} was fixed (04 § 3's {@code RESOLVED}).
     *
     * <p>The note is not defaulted anywhere on this path. 04 § 3 makes it mandatory because a
     * resolved row with no note is indistinguishable from a row nobody looked at, and
     * {@link ExceptionRecord}'s own constructor refuses one — this method does not restate that
     * check, it relies on it.
     */
    public ExceptionRecord resolve(ExceptionRecord row, String resolvedBy, String note) {
        return queue.resolve(row, resolvedBy, note);
    }

    // No accept(...) here, deliberately. An acceptance is recorded on the engine's queue and
    // nowhere else — see ExceptionsModule.accept: a copy on this queue would report a clear close
    // gate on a period where the engine had recorded nothing. A method here would be an invitation
    // to keep that copy.

    /** Every id in the queue, in the order raised, for a refusal that has to name the choices. */
    public List<String> ids() {
        List<String> named = new ArrayList<>();
        for (ExceptionRecord record : queue.records()) {
            named.add(idOf(record));
        }
        return named;
    }

    private static String describeAll(List<ExceptionRecord> records) {
        List<String> described = new ArrayList<>(records.size());
        for (ExceptionRecord record : records) {
            described.add(record.describe());
        }
        return String.join("; ", described);
    }
}
