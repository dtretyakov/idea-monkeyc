import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

//! The smallest app the plugin's tests can build, run and step through.
class FixtureApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function getInitialView() as [Views] or [Views, InputDelegates] {
        return [new FixtureView()];
    }
}

class FixtureView extends WatchUi.View {

    hidden var counter as Number = 0;

    function initialize() {
        View.initialize();
    }

    //! Draws the counter in the middle of the screen.
    function onUpdate(dc as Dc) as Void {
        counter += 1;
        // Printed so a test can see the app is not merely launched but running: this is the only
        // thing the simulator relays that the app itself decided to say.
        System.println("onUpdate " + counter);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        dc.drawText(
            dc.getWidth() / 2,
            dc.getHeight() / 2,
            Graphics.FONT_SMALL,
            counter.toString(),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
        );
    }
}
