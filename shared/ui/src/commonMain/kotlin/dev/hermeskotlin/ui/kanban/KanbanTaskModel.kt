package dev.hermeskotlin.ui.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanAttachment
import dev.hermeskotlin.core.kanban.KanbanEstimate
import dev.hermeskotlin.core.kanban.KanbanLog
import dev.hermeskotlin.core.kanban.KanbanRun
import dev.hermeskotlin.core.kanban.KanbanTaskDetail
import dev.hermeskotlin.core.kanban.KanbanTaskPatch
import dev.hermeskotlin.core.kanban.kanbanStatusLabel
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A comment the app is sending. The server's reply is `{ok:true}` and nothing else — the row only becomes real on
 * the next detail read, so it stays local until a refresh carries its text back.
 */
data class KanbanPendingComment(val key: Long, val body: String, val sending: Boolean = true, val failed: Boolean = false)

/** A destructive step waiting for its confirmation; nothing is sent while it is set. */
sealed interface KanbanConfirm {
    data class StopRun(val run: KanbanRun) : KanbanConfirm
    data class DeleteFile(val attachment: KanbanAttachment) : KanbanConfirm
    data class Finish(val status: String) : KanbanConfirm
}

/** What the task sheet draws: the detail, the comment being sent, and the call in flight. */
data class KanbanTaskState(
    val taskId: String? = null,
    val detail: KanbanTaskDetail? = null,
    val loading: Boolean = false,
    /** Why the detail didn't load, for the sheet's empty state. */
    val error: String? = null,
    val pending: List<KanbanPendingComment> = emptyList(),
    /** Whether the description is open for editing. */
    val editing: Boolean = false,
    val estimate: KanbanEstimate? = null,
    val estimating: Boolean = false,
    val log: KanbanLog? = null,
    /** The run whose log is open. The API serves one log per task, so the bytes are the worker's, whatever the run. */
    val logRun: KanbanRun? = null,
    val logLoading: Boolean = false,
    val confirm: KanbanConfirm? = null,
    /** A status, terminate or delete is on its way. */
    val busy: Boolean = false,
    /** The last outcome of a write, as a banner until dismissed; never a silent success. */
    val notice: String? = null,
)

/**
 * One open card's detail: body, comments, events, runs, attachments. Reads the board's [KanbanRepository] for the
 * gateway and slug, and asks its caller to refresh the board after every write so the lane and counters agree with
 * what the sheet just did.
 */
class KanbanTaskModel(
    private val api: KanbanApi,
    private val gateway: GatewayUrl,
    /** The slug the board is showing; null lets the server answer for its own current board. */
    private val board: String?,
    private val scope: CoroutineScope,
    /** Called after a write lands, so the board under the sheet re-reads its lane and counters. */
    private val onChanged: () -> Unit = {},
    /** Free text the gateway never checks: it is what the comment thread shows as the author. */
    private val author: String = "app",
) {

    private val _state = MutableStateFlow(KanbanTaskState())
    val state: StateFlow<KanbanTaskState> = _state.asStateFlow()

    private var readJob: Job? = null
    private var nextKey = -1L

    /** Opens [taskId]: the first read. A running card's changes arrive through [refresh] on the sheet's poll. */
    fun open(taskId: String) {
        if (taskId == _state.value.taskId) return
        _state.value = KanbanTaskState(taskId = taskId, loading = true)
        read(taskId, silent = false)
    }

    /** Closes the sheet: no more reads land on a card the user has left. */
    fun close() {
        readJob?.cancel()
        _state.value = KanbanTaskState()
    }

    /** The user's own reload; a failure is worth showing, unlike a silent poll's. */
    fun reload() = _state.value.taskId?.let { read(it, silent = false) }

    /** A poll: the same read, but a failure leaves the screen as it is. */
    fun refresh() = _state.value.taskId?.let { read(it, silent = true) }

    private fun read(taskId: String, silent: Boolean) {
        readJob?.cancel()
        _state.update { it.copy(loading = !silent, error = if (silent) it.error else null) }
        readJob = scope.launch {
            when (val result = api.task(gateway, taskId, board)) {
                is ApiResult.Success -> _state.update { state ->
                    if (state.taskId != taskId) return@update state
                    state.copy(
                        detail = result.value,
                        loading = false,
                        error = null,
                        // A sent comment drops out once the server's own row carries its text.
                        pending = state.pending.filter { row ->
                            row.sending || row.failed || result.value.comments.none { it.body.trim() == row.body }
                        },
                    )
                }
                else -> if (!silent) _state.update { it.copy(loading = false, error = result.errorMessage ?: "Couldn't load the task.") }
            }
        }
    }

    // ---- comments ----

    /** Inserts the row at once and sends it; a failure leaves it marked, with a retry. */
    fun comment(text: String) {
        val body = text.trim()
        val taskId = _state.value.taskId ?: return
        if (body.isEmpty()) return
        val row = KanbanPendingComment(nextKey--, body)
        _state.update { it.copy(pending = it.pending + row, notice = null) }
        scope.launch { deliver(taskId, row) }
    }

    fun retry(comment: KanbanPendingComment) {
        val taskId = _state.value.taskId ?: return
        _state.update { it.copy(pending = it.pending.map { if (it.key == comment.key) it.copy(sending = true, failed = false) else it }) }
        scope.launch { deliver(taskId, comment) }
    }

    /** Drops a comment that never went, so the feed doesn't keep a red row the user has given up on. */
    fun discard(comment: KanbanPendingComment) =
        _state.update { it.copy(pending = it.pending.filterNot { row -> row.key == comment.key }) }

    private suspend fun deliver(taskId: String, row: KanbanPendingComment) {
        when (val result = api.addComment(gateway, taskId, row.body, author, board)) {
            is ApiResult.Success -> {
                _state.update { state -> state.copy(pending = state.pending.map { if (it.key == row.key) it.copy(sending = false) else it }) }
                onChanged()
                read(taskId, silent = true)
            }
            else -> failed(row, result.errorMessage ?: "Couldn't send the comment.")
        }
    }

    private fun failed(row: KanbanPendingComment, message: String) = _state.update { state ->
        state.copy(
            pending = state.pending.map { if (it.key == row.key) it.copy(sending = false, failed = true) else it },
            notice = message,
        )
    }

    // ---- description ----

    fun editBody() = _state.update { it.copy(editing = true, notice = null) }

    fun cancelEdit() = _state.update { it.copy(editing = false) }

    fun saveBody(body: String) {
        val taskId = _state.value.taskId ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        scope.launch {
            when (val result = api.updateTask(gateway, taskId, KanbanTaskPatch(body = body), board)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(busy = false, editing = false, notice = "Description saved.", detail = it.detail?.copy(task = result.value))
                    }
                    onChanged()
                }
                else -> _state.update { it.copy(busy = false, notice = result.errorMessage ?: "Couldn't save the description.") }
            }
        }
    }

    // ---- status ----

    /** Moves the card; finishing it is destructive, so it asks first. */
    fun move(status: String) {
        if (status == _state.value.detail?.task?.status) return
        if (status == "done") {
            _state.update { it.copy(confirm = KanbanConfirm.Finish(status)) }
            return
        }
        sendStatus(status)
    }

    private fun sendStatus(status: String) {
        val taskId = _state.value.taskId ?: return
        write(taskId, "Moved to ${kanbanStatusLabel(status).lowercase()}.", "Couldn't move the card.") { url ->
            api.updateTask(url, taskId, KanbanTaskPatch(status = status), board)
        }
    }

    // ---- runs and the log ----

    fun openLog(run: KanbanRun) {
        val taskId = _state.value.taskId ?: return
        if (_state.value.logRun?.id == run.id && _state.value.log != null) return
        _state.update { it.copy(logRun = run, logLoading = true, log = null) }
        scope.launch {
            when (val result = api.log(gateway, taskId, board = board)) {
                is ApiResult.Success -> _state.update { it.copy(logLoading = false, log = result.value) }
                else -> _state.update { it.copy(logLoading = false, notice = result.errorMessage ?: "Couldn't read the worker log.") }
            }
        }
    }

    fun closeLog() = _state.update { it.copy(logRun = null, log = null, logLoading = false) }

    // ---- estimate, attachments, confirmations ----

    fun estimate() {
        val taskId = _state.value.taskId ?: return
        if (_state.value.estimating) return
        _state.update { it.copy(estimating = true, notice = null) }
        scope.launch {
            when (val result = api.estimateTask(gateway, taskId, board)) {
                is ApiResult.Success -> _state.update {
                    it.copy(estimating = false, estimate = result.value, notice = result.value.reason?.takeIf { _ -> !result.value.ok })
                }
                else -> _state.update { it.copy(estimating = false, notice = result.errorMessage ?: "Couldn't estimate the task.") }
            }
        }
    }

    /** The file's bytes, fetched through the gateway route a phone can reach; null says why in [KanbanTaskState.notice]. */
    suspend fun download(attachment: KanbanAttachment): ByteArray? {
        return when (val result = api.attachment(gateway, attachment.id, board)) {
            is ApiResult.Success -> result.value
            else -> {
                _state.update { it.copy(notice = result.errorMessage ?: "Couldn't download the file.") }
                null
            }
        }
    }

    fun askStop(run: KanbanRun) = _state.update { it.copy(confirm = KanbanConfirm.StopRun(run)) }

    fun askDeleteFile(attachment: KanbanAttachment) = _state.update { it.copy(confirm = KanbanConfirm.DeleteFile(attachment)) }

    fun cancelConfirm() = _state.update { it.copy(confirm = null) }

    /** The confirmed step, sent once: the dialog is cleared before the call so a double tap can't send twice. */
    fun confirm() {
        val confirm = _state.value.confirm ?: return
        val taskId = _state.value.taskId ?: return
        _state.update { it.copy(confirm = null) }
        when (confirm) {
            is KanbanConfirm.StopRun -> write(taskId, "Stopped run ${confirm.run.id}.", "Couldn't stop the run.") { url ->
                api.terminateRun(url, confirm.run.id, reason = "stopped from the app", board = board)
            }
            is KanbanConfirm.DeleteFile -> write(taskId, "File deleted.", "Couldn't delete the file.") {
                api.deleteAttachment(it, confirm.attachment.id)
            }
            is KanbanConfirm.Finish -> sendStatus(confirm.status)
        }
    }

    fun tell(message: String) = _state.update { it.copy(notice = message) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** One write's bookkeeping: busy while it flies, then a notice, a board refresh and a silent re-read. */
    private fun <T> write(taskId: String, success: String, failure: String, block: suspend (GatewayUrl) -> ApiResult<T>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        scope.launch {
            when (val result = block(gateway)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(busy = false, notice = success) }
                    onChanged()
                    read(taskId, silent = true)
                }
                else -> _state.update { it.copy(busy = false, notice = result.errorMessage ?: failure) }
            }
        }
    }
}
