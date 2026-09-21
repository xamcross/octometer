package octometer.kit.core.ingest;

/**
 * One click entry of a parsed ingest body, before the field validation of
 * rule C4 and the `ts` computation of rule C14.
 *
 * <p>Version 1.1 of the contract adds two optional fields: `path` (rule
 * C39) and `referrerHost` (rule C40). Each one is {@code null} when the
 * body holds no such field, or when the body holds a value of a JSON
 * type other than a string. {@link IngestPipeline#process} holds the
 * shape check of each field and the drop rule of rule C41.
 */
record ParsedClick(String element, long ageMs, String path, String referrerHost) {
}
