package app.btcourier.work

/** Records every scheduling request, for the view model and work rule tests. */
class RecordingWorkControl : WorkControl {
    /** Addresses passed to [syncNow], in order. */
    val syncs = mutableListOf<String>()
    val uploads = mutableListOf<Pair<Boolean, Boolean>>()

    /** `delayMs to allowCellular` passed to [uploadAt], in order. */
    val deferredUploads = mutableListOf<Pair<Long, Boolean>>()
    val periodic = mutableListOf<Boolean>()

    /** `cancel`, `upload` and `forget <sink id>` in call order, to check what precedes what. */
    val calls = mutableListOf<String>()

    /** Runs inside [cancelUpload], to observe state at the moment of cancelling. */
    var onCancelUpload: () -> Unit = {}

    override fun syncNow(address: String) {
        syncs += address
    }

    override fun uploadNow(allowCellular: Boolean, replace: Boolean) {
        uploads += allowCellular to replace
        calls += "upload"
    }

    override fun uploadAt(delayMs: Long, allowCellular: Boolean) {
        deferredUploads += delayMs to allowCellular
        calls += "upload-at"
    }

    override fun cancelUpload() {
        onCancelUpload()
        calls += "cancel"
    }

    override fun setPeriodicSync(enabled: Boolean) {
        periodic += enabled
    }

    override fun forgetRetries(sinkId: String) {
        calls += "forget $sinkId"
    }
}
