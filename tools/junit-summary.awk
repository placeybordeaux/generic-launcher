# tools/junit-summary.awk — totals from Gradle's per-class JUnit XML.
#
#   awk -f tools/junit-summary.awk app/build/test-results/testDebugUnitTest/*.xml
#   -> "<total> <failed> <skipped> <files>"
#
# POSIX awk only. The previous version of this used `grep -oP`, which stock macOS BSD grep
# does not support — it worked on one machine because `grep` there was ugrep, and anywhere
# else it silently produced "Total: 0 | Failed: 0" and then "ALL TESTS PASSED".

# Pull integer attribute k out of an element. The leading space in the pattern stops
# "tests" from matching inside another attribute's value.
function attr(s, k,   v) {
    if (match(s, " " k "=\"[0-9]+\"")) {
        v = substr(s, RSTART, RLENGTH)
        sub(/^[^"]*"/, "", v)
        sub(/"$/, "", v)
        return v + 0
    }
    return 0
}

FNR == 1 { seen = 0; files++ }

# Only the first <testsuite> per file: the outer element already aggregates its children,
# so counting nested ones would double-count.
!seen && /<testsuite / {
    seen = 1
    total   += attr($0, "tests")
    failed  += attr($0, "failures") + attr($0, "errors")
    skipped += attr($0, "skipped")
}

END { printf "%d %d %d %d\n", total, failed, skipped, files }
