package octometer.kit.core.ingest;

/**
 * One click entry of a parsed ingest body, before the field validation of
 * rule C4 and the `ts` computation of rule C14.
 */
public record ParsedClick(String element, long ageMs) {
}
