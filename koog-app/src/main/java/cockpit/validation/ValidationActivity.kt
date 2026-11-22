package cockpit.validation
import cockpit.ui.RuntimeCockpitActivity
import cockpit.RuntimeAdapter
class ValidationActivity : RuntimeCockpitActivity() {
    override fun adapter(): RuntimeAdapter = cockpit.koog.KoogAdapter()
}
