package io.github.taehunkim.jogakbo;

/**
 * Jogakbo's notes as a bound service (action Jogakbo.ACTION_BIND), for apps the user allowed (an assistant chat).
 * Everything stays on the phone: the caller gets note text and ids.
 *
 * Versioning: methods are only ever appended (explicit transaction ids), never reordered, removed or changed; each
 * notes the API level that added it. Call hello() first. Payloads are JSON strings so fields can be added without
 * breaking older clients (they ignore unknown fields). Errors arrive as IllegalStateException encoded by
 * JogakboErrors (codes in Jogakbo.Errors), or SecurityException for refused callers.
 */
interface INotes {
    int apiVersion() = 1;

    /**
     * API 1. Exchanges versions. client: {"api":1,"sdk":"0.1.0"}. Returns {"api":1,"minClientApi":1,"appVersion":"0.1.0"}.
     * Throws CLIENT_TOO_OLD when the client's API is below the minimum.
     */
    String hello(String clientJson) = 2;

    /** API 1. Tools a chat model may call, as a JSON array of {"name","description","parameters"(JSON schema),"confirm"}. */
    String tools() = 3;

    /** API 1. Runs a tool with JSON arguments; returns the text the model sees ("Error: ..." on bad input). */
    String runTool(String name, String argsJson) = 4;

    /**
     * API 1. Facts about notes: JSON array of {"note_id","title","kind","created","updated","url"}; unknown ids are
     * left out. kind: memo, link, youtube, audio. Dates are yyyy-MM-dd HH:mm in the phone's time zone.
     */
    String notes(in long[] ids) = 5;

    /** API 1. A note as Markdown (title, summary, then the original) up to maxChars; "" for an unknown id. */
    String readNote(long id, int maxChars) = 6;

    /**
     * API 1. Notes added or changed after cursor (0 = from now on: returns no ids, only the current cursor).
     * Returns {"ids":[...],"cursor":N}; pass the cursor back next time.
     */
    String changesSince(long cursor, int limit) = 7;
}
