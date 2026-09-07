import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
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
