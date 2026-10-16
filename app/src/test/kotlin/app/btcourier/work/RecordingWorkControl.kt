package app.btcourier.work

/** Records every scheduling request, for the view model and work rule tests. */
class RecordingWorkControl : WorkControl {
    /** Addresses of automatic [syncNow]s, in order. */
    val syncs = mutableListOf<String>()

    /** Addresses of manual [syncNow]s, in order. */
    val manualSyncs = mutableListOf<String>()
    val uploads = mutableListOf<Pair<Boolean, Boolean>>()

    /** `delayMs to allowCellular` passed to [uploadAt] for [FollowUp.SINK_RETRY], in order. */
    val deferredUploads = mutableListOf<Pair<Long, Boolean>>()

    /** `delayMs to allowCellular` passed to [uploadAt] for [FollowUp.SEAL], in order. */
    val sealUploads = mutableListOf<Pair<Long, Boolean>>()
    val periodic = mutableListOf<Boolean>()

    /** `cancel`, `upload` and `forget <sink id>` in call order, to check what precedes what. */
    val calls = mutableListOf<String>()

    /** Runs inside [cancelUpload], to observe state at the moment of cancelling. */
    var onCancelUpload: () -> Unit = {}

    override fun syncNow(
        address: String,
        manual: Boolean,
    ) {
        if (manual) manualSyncs += address else syncs += address
    }

    override fun uploadNow(
        allowCellular: Boolean,
        replace: Boolean,
    ) {
        uploads += allowCellular to replace
        calls += "upload"
    }

    override fun uploadAt(
        delayMs: Long,
        allowCellular: Boolean,
        slot: FollowUp,
    ) {
        val slotUploads =
            when (slot) {
                FollowUp.SINK_RETRY -> deferredUploads
                FollowUp.SEAL -> sealUploads
            }
        slotUploads += delayMs to allowCellular
        calls += "upload-at"
    }

    override fun cancelUpload() {
        onCancelUpload()
        calls += "cancel"
    }

    override fun setPeriodicSync(enabled: Boolean) {
        periodic += enabled
    }

    override suspend fun forgetRetries(sinkId: String) {
        calls += "forget $sinkId"
    }
}
