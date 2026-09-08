import Toybox.Lang;
import Toybox.Test;

//! Inside the module on purpose.
//!
//! `barrelbuild` refuses anything that is not in the barrel's own namespace — "Function
//! 'barrelDoubles' does not belong to the Monkey Barrel namespace" — so a test at the top level
//! breaks the barrel itself, not only its tests.
module FixtureBarrel {
    (:test)
    function barrelDoubles(logger as Logger) as Boolean {
        return twice(21) == 42;
    }
}
