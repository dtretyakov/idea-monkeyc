import Toybox.Lang;
import Toybox.Test;

//! Unit tests, so that a test build of the fixture actually has something to run.
//!
//! Not a detail: a `--unit-test` build with no `(:test)` function anywhere starts, finds nothing,
//! prints nothing and exits — which is indistinguishable from a debug adapter that never launched
//! the app at all. Keeping a passing and a failing test here means every live test that runs the
//! fixture can tell those two apart.

(:test)
function fixturePasses(logger as Logger) as Boolean {
    logger.debug("the test that passes");
    return true;
}

(:test)
function fixtureFails(logger as Logger) as Boolean {
    logger.debug("the test that fails");
    return false;
}
