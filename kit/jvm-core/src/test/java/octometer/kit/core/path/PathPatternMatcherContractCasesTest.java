package octometer.kit.core.path;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reads `contract/examples/C42-path-match-cases.json` (27 cases, issue
 * #106) and asserts each one against {@link PathPatternMatcher}.
 * `kit/tracker/src/path-match.ts` reads the same file for the tracker
 * (issue #106), so the two matchers give the same result for each case
 * (issue #104).
 */
class PathPatternMatcherContractCasesTest {

    @TestFactory
    List<DynamicTest> matchesEachSharedCase() {
        String json = ContractExampleFile.read("C42-path-match-cases.json");
        List<Object> rawCases = MiniJson.parseArray(json);
        List<DynamicTest> tests = new ArrayList<>();
        for (Object rawCase : rawCases) {
            Map<?, ?> testCase = (Map<?, ?>) rawCase;
            String name = (String) testCase.get("name");
            List<String> routes = castStringList(testCase.get("routes"));
            String path = (String) testCase.get("path");
            String expectedResult = (String) testCase.get("result");
            tests.add(DynamicTest.dynamicTest(name, () -> {
                // The case "an empty route list gives /other inside the
                // matcher function" exercises the matcher with zero
                // patterns. An app never reaches this state: IngestSettings
                // builds no PathPatternMatcher for an absent list (rule
                // C42). PathPatternMatcher.of accepts an empty list too, so
                // the case still runs through the production match method.
                String actualResult = PathPatternMatcher.of(routes).match(path);
                assertEquals(expectedResult, actualResult, name);
            }));
        }
        return tests;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStringList(Object value) {
        return (List<String>) (List<?>) value;
    }
}
