/**
 * Employee Dashboard — Apply Leave (optimized: bootstrap, client log filter, caches, toasts).
 */
(function () {
    'use strict';

    var PAGE_SIZE = 25;
    var LOGS_PAGE_SIZE = 50;
    var loaded = false;
    var loading = false;
    var prefetchStarted = false;
    var historyLoading = false;
    var logsMasterLoading = false;
    var submitBusy = false;
    var cancelBusy = false;
    var dayType = 'full';
    var pendingCancelId = null;
    var cancelModal = null;
    var inflight = { bootstrap: null, history: null, logs: null };

    var formsBound = false;
    var scrollBound = false;

    var historyCache = {};
    var logsMaster = null;
    var logsMasterPromise = null;
    var logsIndex = null;
    var logsHtmlCache = {};
    var logsVisibleCount = PAGE_SIZE;

    /* Teaching substitute workflow */
    var isTeaching = false;
    var pendingSubstitutes = [];
    var busySessionsCache = null;
    var busySessionsRangeKey = '';
    var facultyOptionsCache = null;
    var priorModal = null;
    var priorSelects = {};
    var busyAbort = null;
    var busyDebounceTimer = null;
    var substitutesConfirmedForRange = '';
    var confirmedSubstitutesByRange = {};
    var scheduleLoadState = 'idle'; // idle|loading|ready|error
    var scheduleLoadError = '';

    function todayLocalIso() {
        var d = new Date();
        return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
    }

    function getLeaveDateRange() {
        var from = (document.getElementById('lv-date-from') || {}).value || '';
        var to = (document.getElementById('lv-date-to') || {}).value || '';
        return { from: from, to: to };
    }

    function leaveRangeKey(from, to) {
        return String(from || '') + '|' + String(to || '');
    }

    function inclusiveCalendarDays(from, to) {
        if (!from || !to || from > to) return 0;
        var a = new Date(from + 'T00:00:00');
        var b = new Date(to + 'T00:00:00');
        if (isNaN(a.getTime()) || isNaN(b.getTime())) return 0;
        return Math.round((b.getTime() - a.getTime()) / 86400000) + 1;
    }

    function getRequestedLeaveDays(from, to) {
        if (!from || !to || from > to) return 0;
        if (dayType === 'half' && from === to) return 0.5;
        if (dayType === 'half' && from !== to) return 0;
        return inclusiveCalendarDays(from, to);
    }

    function getSelectedLeaveBalance() {
        var category = ((document.getElementById('lv-type') || {}).value || '').trim();
        if (!category) return null;
        if (category.toUpperCase() === 'OD') {
            return { name: category, available: Infinity, isOd: true };
        }
        var balances = (window.ED_LEAVE && window.ED_LEAVE.balances) || [];
        for (var i = 0; i < balances.length; i++) {
            var b = balances[i];
            if (String(b.name || '').trim() === category) {
                var raw = b.availability;
                if (raw === 'NA' || raw === null || raw === undefined || raw === '') {
                    return { name: category, available: 0, isOd: false };
                }
                var avail = parseFloat(raw);
                return {
                    name: category,
                    available: isNaN(avail) ? 0 : avail,
                    isOd: false,
                };
            }
        }
        return { name: category, available: 0, isOd: false };
    }

    function getBalanceShortfallMessage(days, balance) {
        if (!balance || balance.isOd || !(days > 0)) return '';
        if (days <= balance.available) return '';
        var availLabel = Number(balance.available) === balance.available && balance.available % 1 !== 0
            ? String(balance.available)
            : String(balance.available);
        var needLabel = days === 0.5 ? '0.5' : String(days);
        return 'Insufficient ' + balance.name + ' balance. You selected '
            + needLabel + ' day' + (days === 1 || days === 0.5 ? '' : 's')
            + ' but only ' + availLabel + ' day' + (balance.available === 1 ? '' : 's')
            + ' available. Please reduce the date range or choose another leave type.';
    }

    function updateDaysHint() {
        var el = document.getElementById('lv-days-hint');
        if (!el) {
            syncSubmitButton();
            return;
        }
        var range = getLeaveDateRange();
        if (!range.from || !range.to) {
            el.style.display = 'none';
            el.textContent = '';
            el.style.color = '';
            el.style.background = '';
            el.style.borderColor = '';
            syncSubmitButton();
            return;
        }
        if (range.from > range.to) {
            el.style.display = 'block';
            el.style.color = '#b91c1c';
            el.style.background = '#fef2f2';
            el.style.borderColor = '#fca5a5';
            el.textContent = 'From date cannot be after To date.';
            syncSubmitButton();
            return;
        }
        if (dayType === 'half' && range.from !== range.to) {
            el.style.display = 'block';
            el.style.color = '#b91c1c';
            el.style.background = '#fef2f2';
            el.style.borderColor = '#fca5a5';
            el.textContent = 'Half day leave is only allowed when From and To are the same date.';
            syncSubmitButton();
            return;
        }

        var days = getRequestedLeaveDays(range.from, range.to);
        var balance = getSelectedLeaveBalance();
        var shortfall = getBalanceShortfallMessage(days, balance);

        el.style.display = 'block';
        if (shortfall) {
            el.style.color = '#b91c1c';
            el.style.background = '#fef2f2';
            el.style.borderColor = '#fca5a5';
            el.textContent = shortfall;
            syncSubmitButton();
            return;
        }

        el.style.color = '#0369a1';
        el.style.background = '#e0f2fe';
        el.style.borderColor = '#7dd3fc';
        if (!balance) {
            el.textContent = days === 0.5
                ? 'Half day (0.5) will be deducted on submit. Select a leave type to verify balance.'
                : (days + ' day' + (days === 1 ? '' : 's') + ' will be deducted on submit. Select a leave type to verify balance.');
            syncSubmitButton();
            return;
        }
        if (balance.isOd) {
            el.textContent = days === 0.5
                ? 'Half day OD — balance is not deducted.'
                : (days + ' day' + (days === 1 ? '' : 's') + ' OD — balance is not deducted.');
            syncSubmitButton();
            return;
        }
        el.textContent = days === 0.5
            ? 'Half day (0.5) will be deducted on submit. Available: ' + balance.available + '.'
            : (days + ' day' + (days === 1 ? '' : 's') + ' will be deducted on submit. Available: ' + balance.available + '.');
        syncSubmitButton();
    }

    /**
     * Whether the apply form is complete and free of blocking errors.
     * @returns {{ ok: boolean, reason: string }}
     */
    function getFormReadiness() {
        if (submitBusy) {
            return { ok: false, reason: 'Submitting…' };
        }

        var category = ((document.getElementById('lv-type') || {}).value || '').trim();
        var range = getLeaveDateRange();
        var reason = ((document.getElementById('lv-reason') || {}).value || '').trim();
        var session = ((document.getElementById('lv-sess') || {}).value || '').trim();
        var fileInput = document.getElementById('lv-file');
        var file = fileInput && fileInput.files ? fileInput.files[0] : null;
        var rangeKey = leaveRangeKey(range.from, range.to);

        if (!category) return { ok: false, reason: 'Select a leave type' };
        if (!range.from || !range.to) return { ok: false, reason: 'Choose from and to dates' };
        if (range.from > range.to) return { ok: false, reason: 'From date cannot be after To date' };
        if (dayType === 'half' && range.from !== range.to) {
            return { ok: false, reason: 'Half day requires the same From and To date' };
        }
        if (dayType === 'half' && !session) {
            return { ok: false, reason: 'Select Session-1 or Session-2' };
        }

        var requestedDays = getRequestedLeaveDays(range.from, range.to);
        var balance = getSelectedLeaveBalance();
        var shortfall = getBalanceShortfallMessage(requestedDays, balance);
        if (shortfall) return { ok: false, reason: shortfall };

        if (!reason) return { ok: false, reason: 'Enter a reason for leave' };
        if (!file) return { ok: false, reason: 'Upload a supporting image' };
        if (file.size > 10 * 1024 * 1024) return { ok: false, reason: 'Image must be 10 MB or smaller' };
        if (!/^image\/(jpeg|png)$/i.test(file.type)) return { ok: false, reason: 'Only PNG and JPEG images are allowed' };

        if ((isTeaching || (window.ED_LEAVE && window.ED_LEAVE.is_teaching))
            && !isRetroactiveLeaveRange(range.from, range.to)) {
            if (scheduleLoadState === 'loading' || scheduleLoadState === 'idle') {
                return { ok: false, reason: 'Checking your schedule…' };
            }
            if (scheduleLoadState === 'error') {
                return { ok: false, reason: scheduleLoadError || 'Could not load schedule for these dates' };
            }
            if (teachingNeedsSubstitutes()) {
                if (substitutesConfirmedForRange !== rangeKey || !pendingSubstitutes.length) {
                    return { ok: false, reason: 'Assign substitutes for all scheduled sessions' };
                }
            }
        }

        return { ok: true, reason: '' };
    }

    function syncSubmitButton() {
        var btn = document.getElementById('lv-submit-btn');
        if (!btn) return;
        var ready = getFormReadiness();
        if (submitBusy) {
            btn.disabled = true;
            btn.textContent = 'Submitting...';
            btn.title = 'Submitting…';
            btn.style.opacity = '0.65';
            btn.style.cursor = 'not-allowed';
            return;
        }
        btn.disabled = !ready.ok;
        btn.textContent = 'Submit Application';
        btn.title = ready.ok ? 'Submit leave application' : (ready.reason || 'Complete all required fields');
        btn.style.opacity = ready.ok ? '' : '0.65';
        btn.style.cursor = ready.ok ? '' : 'not-allowed';
    }

    function isRetroactiveLeaveDate(leaveDate) {
        var today = (window.ED_LEAVE && window.ED_LEAVE.today) ? String(window.ED_LEAVE.today) : todayLocalIso();
        return !!(leaveDate && leaveDate < today);
    }

    /** Entire range is past when the last day is before today. */
    function isRetroactiveLeaveRange(from, to) {
        var end = to || from;
        return isRetroactiveLeaveDate(end);
    }

    function setPastDateNote(show) {
        var el = document.getElementById('lv-past-date-note');
        if (el) el.style.display = show ? 'block' : 'none';
    }

    function courseKeyFromSession(s) {
        var offering = String((s && (s.offering_code || s.offeringCode)) || '').trim();
        if (offering) return offering;
        return String((s && (s.course_code || s.courseCode)) || '').trim();
    }

    function sessionDateOf(sessionOrPick) {
        return String((sessionOrPick && (sessionOrPick.session_date || sessionOrPick.date)) || '').trim();
    }

    function sessionPickKey(sessionOrPick) {
        var range = getLeaveDateRange();
        var date = sessionDateOf(sessionOrPick) || range.from || '';
        var num = Number((sessionOrPick && sessionOrPick.session_number) || 0);
        return date + '#' + num + '#' + courseKeyFromSession(sessionOrPick);
    }

    function filterSessionsForDayType(sessions) {
        var list = Array.isArray(sessions) ? sessions.slice() : [];
        if (dayType !== 'half') return list;
        var sess = (document.getElementById('lv-sess') || {}).value || '';
        var want = sess === 'Session-2' ? 2 : (sess === 'Session-1' ? 1 : 0);
        if (!want) return list;
        return list.filter(function (s) { return Number(s.session_number || 0) === want; });
    }

    function apiUrl(query) {
        if (typeof window.edApiUrl === 'function') {
            return window.edApiUrl('leave', query);
        }
        var base = (window.ED_CONFIG && window.ED_CONFIG.apiBase) || '/api/employee';
        var url = base.replace(/\/$/, '') + '/leave.php';
        if (query) {
            url += (url.indexOf('?') >= 0 ? '&' : '?') + new URLSearchParams(query).toString();
        }
        return url;
    }

    function defaultLeaveState() {
        return {
            balances: [],
            message: '',
            cycle: null,
            leave_filter_names: [],
            history: [],
            historyOffset: 0,
            hasMoreHistory: false,
            historyLoaded: false,
            historyFilters: { date_from: '', date_to: '' },
            logsFilter: '',
            loaded: false,
            error: null,
        };
    }

    function esc(text) {
        var el = document.createElement('span');
        el.textContent = text == null ? '' : String(text);
        return el.innerHTML;
    }

    function historyCacheKey(filters, offset) {
        var f = filters || {};
        return [f.date_from || '', f.date_to || '', offset || 0].join('|');
    }

    function toastIcon(type) {
        if (type === 'success') return 'fa-check';
        if (type === 'error') return 'fa-circle-xmark';
        if (type === 'warning') return 'fa-triangle-exclamation';
        return 'fa-circle-info';
    }

    function toastTitle(type) {
        if (type === 'success') return 'Success';
        if (type === 'error') return 'Error';
        if (type === 'warning') return 'Warning';
        return 'Notice';
    }

    function showToast(message, type, title) {
        var stack = document.getElementById('ed-toast-stack');
        if (!stack) return;

        type = type || 'info';
        var node = document.createElement('div');
        node.className = 'ed-toast ed-toast-' + type;
        node.innerHTML =
            '<div class="ed-toast-icon"><i class="fa ' + toastIcon(type) + '"></i></div>'
            + '<div class="ed-toast-body">'
            + '<div class="ed-toast-title">' + esc(title || toastTitle(type)) + '</div>'
            + '<div class="ed-toast-msg">' + esc(message) + '</div>'
            + '</div>'
            + '<button type="button" class="ed-toast-close" aria-label="Dismiss">&times;</button>';

        var close = function () {
            if (!node.parentNode) return;
            node.classList.add('is-leaving');
            setTimeout(function () { node.remove(); }, 220);
        };

        node.querySelector('.ed-toast-close').addEventListener('click', close);
        stack.appendChild(node);
        setTimeout(close, type === 'error' ? 6000 : 4200);
    }

    function mapFetchError(err, fallback) {
        if (!err) return fallback || 'Something went wrong.';
        if (err.name === 'AbortError') return null;
        if (err.message === 'Failed to fetch' || err.message === 'NetworkError when attempting to fetch resource.') {
            return 'Network error. Check your connection and try again.';
        }
        var msg = String(err.message || '').trim();
        if (!msg) return fallback || 'Something went wrong.';
        // Never surface HTML/SQL/stack dumps from upstream failures.
        if (/Invalid server response/i.test(msg)
            || /<\/?[a-z][\s\S]*>/i.test(msg)
            || /SQLSTATE|mysqli_|Fatal error|Stack trace|PDOException/i.test(msg)
            || /Failed to prepare|Failed to (lock|fetch|update|insert)/i.test(msg)) {
            return fallback || 'Something went wrong. Please try again.';
        }
        return msg;
    }

    function fetchJson(url, options) {
        return fetch(url, Object.assign({
            credentials: 'same-origin',
            headers: { Accept: 'application/json' },
        }, options || {})).then(function (res) {
            return res.text().then(function (text) {
                var json = null;
                try { json = text ? JSON.parse(text) : null; } catch (e) { json = null; }

                if (!json || typeof json !== 'object') {
                    var snippet = (text || '').replace(/\s+/g, ' ').trim().slice(0, 120);
                    var err = new Error(snippet ? 'Invalid server response: ' + snippet : 'Invalid server response');
                    err.status = res.status;
                    throw err;
                }

                if (!res.ok || !json.success) {
                    var fail = new Error(json.error || ('Request failed (' + res.status + ')'));
                    fail.status = res.status;
                    fail.code = json.code || null;
                    throw fail;
                }

                return json.data;
            });
        });
    }

    function pill(status) {
        if (typeof window.pill === 'function') return window.pill(status);
        var st = String(status || '').toLowerCase();
        var cls = st === 'approved' ? 'sp-approved' : st === 'rejected' ? 'sp-rejected' : 'sp-pending';
        return '<span class="sp ' + cls + '">' + esc(status) + '</span>';
    }

    function formatAppliedOn(value) {
        if (!value) return '—';
        var d = new Date(value);
        if (isNaN(d.getTime())) return esc(value);
        return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
    }

    function formatLeaveDate(value) {
        if (!value) return '—';
        var d = new Date(value + 'T00:00:00');
        if (isNaN(d.getTime())) return esc(value);
        return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
    }

    function formatLeaveDateRange(start, end) {
        var from = start || '';
        var to = end || start || '';
        if (!from) return '—';
        if (!to || to === from) return formatLeaveDate(from);
        return formatLeaveDate(from) + ' – ' + formatLeaveDate(to);
    }

    function setPaneLoading(paneId, on) {
        var el = document.getElementById(paneId);
        if (!el) return;
        el.classList.toggle('lv-pane-loading', !!on);
    }

    function renderHistorySkeleton(rows) {
        var body = document.getElementById('lvh-body');
        if (!body) return;
        var count = rows || 5;
        var html = '';
        for (var i = 0; i < count; i++) {
            html += '<tr class="lv-skel-row"><td colspan="8"><div class="lv-skel-bar"></div></td></tr>';
        }
        body.innerHTML = html;
    }

    function renderLogsSkeleton(rows) {
        var body = document.getElementById('lvl-body');
        if (!body) return;
        var count = rows || 5;
        var html = '';
        for (var i = 0; i < count; i++) {
            html += '<tr class="lv-skel-row"><td colspan="6"><div class="lv-skel-bar"></div></td></tr>';
        }
        body.innerHTML = html;
    }

    function invalidateCaches() {
        historyCache = {};
        logsMaster = null;
        logsMasterPromise = null;
        logsIndex = null;
        logsHtmlCache = {};
        logsVisibleCount = PAGE_SIZE;
        if (window.ED_LEAVE) {
            window.ED_LEAVE.historyLoaded = false;
            window.ED_LEAVE.historyOffset = 0;
            window.ED_LEAVE.hasMoreHistory = false;
        }
    }

    function logFilterKey(name) {
        return String(name || '');
    }

    function buildLogsIndex(rows) {
        var index = { '': rows };
        var names = (window.ED_LEAVE && window.ED_LEAVE.leave_filter_names) || [];
        names.forEach(function (name) {
            index[logFilterKey(name)] = [];
        });
        rows.forEach(function (row) {
            names.forEach(function (name) {
                if (logMatchesFilter(row, name)) {
                    index[logFilterKey(name)].push(row);
                }
            });
        });
        return index;
    }

    function setLogsMaster(rows) {
        logsMaster = rows || [];
        logsIndex = buildLogsIndex(logsMaster);
        logsHtmlCache = {};
    }

    function applyLogsBlock(block) {
        if (!block || !Array.isArray(block.rows)) return;
        setLogsMaster(block.rows);
    }

    function updateHeaderLeaveBalance(balances) {
        var el = document.getElementById('ed-header-leave');
        if (!el || !Array.isArray(balances) || !balances.length) return;

        var total = 0;
        var hasNumber = false;
        balances.forEach(function (b) {
            var v = parseFloat(b.availability);
            if (!isNaN(v)) {
                total += v;
                hasNumber = true;
            }
        });

        if (!hasNumber) return;
        el.classList.remove('ed-skel-wrap');
        el.textContent = String(total);
    }

    function applyOverview(block) {
        if (!block) return;
        if (!window.ED_LEAVE) window.ED_LEAVE = defaultLeaveState();

        window.ED_LEAVE.balances = block.balances || [];
        window.ED_LEAVE.message = block.message || '';
        window.ED_LEAVE.cycle = block.cycle || null;
        window.ED_LEAVE.leave_filter_names = block.leave_filter_names || [];
        window.ED_LEAVE.is_teaching = !!block.is_teaching;
        window.ED_LEAVE.department = block.department || '';
        window.ED_LEAVE.loaded = true;
        window.ED_LEAVE.error = null;
        loaded = true;
        isTeaching = !!block.is_teaching;

        if (block.cycle) {
            if (!window.ED_LEAVE.historyFilters.date_from) {
                window.ED_LEAVE.historyFilters.date_from = block.cycle.date_from || '';
            }
            if (!window.ED_LEAVE.historyFilters.date_to) {
                window.ED_LEAVE.historyFilters.date_to = block.cycle.date_to || '';
            }
        }

        renderBalances();
        populateLeaveSelect();
        populateLogFilter();
        updateHeaderLeaveBalance(block.balances || []);
        syncHistoryFilterInputs();
        if (logsMaster) {
            logsIndex = buildLogsIndex(logsMaster);
            logsHtmlCache = {};
        }
    }

    function applyHistoryBlock(block, reset) {
        if (!block || !window.ED_LEAVE) return;

        var rows = block.rows || [];
        if (reset) window.ED_LEAVE.history = rows;
        else window.ED_LEAVE.history = (window.ED_LEAVE.history || []).concat(rows);

        window.ED_LEAVE.historyOffset = block.next_offset != null
            ? block.next_offset
            : (window.ED_LEAVE.historyOffset + rows.length);
        window.ED_LEAVE.hasMoreHistory = !!block.has_more;
        window.ED_LEAVE.historyLoaded = true;

        if (block.date_from && block.date_to) {
            window.ED_LEAVE.historyFilters.date_from = block.date_from;
            window.ED_LEAVE.historyFilters.date_to = block.date_to;
        }

        var labelEl = document.getElementById('lv-cycle-label');
        if (labelEl && block.cycle_label) labelEl.textContent = block.cycle_label;

        renderHistoryRows(rows, !reset);
        setPaneLoading('lv-hist-scroll', false);
    }

    function applyPatch(patch) {
        if (!patch) return;
        historyCache = {};
        if (window.ED_LEAVE) {
            window.ED_LEAVE.historyLoaded = false;
            window.ED_LEAVE.historyOffset = 0;
            window.ED_LEAVE.hasMoreHistory = false;
        }
        if (patch.overview) applyOverview(patch.overview);
        if (patch.history) applyHistoryBlock(patch.history, true);
        if (patch.logs) applyLogsBlock(patch.logs);
    }

    function renderBalances() {
        var host = document.getElementById('lv-balances');
        var msgEl = document.getElementById('lv-bal-message');
        if (!host) return;

        var balances = (window.ED_LEAVE && window.ED_LEAVE.balances) || [];
        var message = (window.ED_LEAVE && window.ED_LEAVE.message) || '';

        if (!balances.length) {
            host.innerHTML = '<span class="lv-chip lv-chip-c0">No balances available</span>';
        } else {
            host.innerHTML = balances.map(function (b, idx) {
                var color = 'lv-chip-c' + (idx % 5);
                var label = b.name + (b.abbr ? ' (' + b.abbr + ')' : '');
                return '<span class="lv-chip ' + color + '"><strong>' + esc(b.availability) + '</strong> ' + esc(label) + '</span>';
            }).join('');
        }

        if (msgEl) {
            msgEl.textContent = message || '';
            msgEl.style.display = message ? 'block' : 'none';
        }
    }

    function populateLeaveSelect() {
        var select = document.getElementById('lv-type');
        if (!select) return;

        var balances = (window.ED_LEAVE && window.ED_LEAVE.balances) || [];
        var current = select.value;
        var html = '<option value="">Select leave type...</option>';
        balances.forEach(function (b) {
            var label = b.name + (b.abbr ? ' (' + b.abbr + ')' : '');
            html += '<option value="' + esc(b.name).replace(/"/g, '&quot;') + '">' + esc(label) + '</option>';
        });
        select.innerHTML = html;
        if (current) select.value = current;
    }

    function populateLogFilter() {
        var select = document.getElementById('lv-log-filter');
        if (!select) return;

        var names = (window.ED_LEAVE && window.ED_LEAVE.leave_filter_names) || [];
        var current = select.value || '';
        var html = '<option value="">All Leave Types</option>';
        names.forEach(function (name) {
            html += '<option value="' + esc(name).replace(/"/g, '&quot;') + '">' + esc(name) + '</option>';
        });
        select.innerHTML = html;
        if (current && names.indexOf(current) >= 0) select.value = current;
    }

    function syncHistoryFilterInputs() {
        var cycle = window.ED_LEAVE && window.ED_LEAVE.cycle;
        var filters = (window.ED_LEAVE && window.ED_LEAVE.historyFilters) || {};
        var fromEl = document.getElementById('lv-hist-from');
        var toEl = document.getElementById('lv-hist-to');
        var labelEl = document.getElementById('lv-cycle-label');

        if (fromEl && filters.date_from) fromEl.value = filters.date_from;
        if (toEl && filters.date_to) toEl.value = filters.date_to;
        if (labelEl) labelEl.textContent = cycle && cycle.label ? cycle.label : (labelEl.textContent || '');
    }

    function renderHistoryRows(rows, append) {
        var body = document.getElementById('lvh-body');
        if (!body) return;

        if (!append) body.innerHTML = '';

        if (!rows.length && !append) {
            body.innerHTML = '<tr><td colspan="8" style="text-align:center;padding:24px;color:#94a3b8;font-size:13px">No leave applications in this range.</td></tr>';
            return;
        }

        var html = rows.map(function (r) {
            var statusKey = String(r.status || 'pending').toLowerCase();
            var actionCell = r.can_cancel
                ? '<button type="button" class="lv-filter-btn secondary lv-cancel-btn" data-leave-id="' + r.id + '" style="padding:4px 10px;font-size:10px">Cancel</button>'
                : '—';
            var reason = String(r.reason || '').trim();
            var fileUrl = String(r.file || r.file_url || '').trim();
            var reasonCell = reason
                ? '<td class="lv-hist-reason" title="' + esc(reason).replace(/"/g, '&quot;') + '">' + esc(reason) + '</td>'
                : '<td class="lv-hist-reason" style="color:#94a3b8">—</td>';
            var fileCell = fileUrl
                ? '<td><button type="button" class="lv-file-thumb-btn" data-file-url="' + esc(fileUrl).replace(/"/g, '&quot;') + '" title="View attachment">'
                    + '<img class="lv-file-thumb" src="' + esc(fileUrl).replace(/"/g, '&quot;') + '" alt="Leave attachment">'
                    + '</button></td>'
                : '<td style="color:#94a3b8">—</td>';

            return '<tr>'
                + '<td style="font-weight:600;color:#1a202c">' + esc(r.category) + '</td>'
                + '<td style="color:#6b7280">' + formatLeaveDateRange(r.start_date || r.leave_date, r.end_date || r.leave_date) + '</td>'
                + '<td style="text-align:center;font-weight:700">' + esc(r.total_days) + '</td>'
                + reasonCell
                + fileCell
                + '<td>' + pill(statusKey) + '</td>'
                + '<td style="color:#9ca3af">' + formatAppliedOn(r.applied_on) + '</td>'
                + '<td>' + actionCell + '</td>'
                + '</tr>';
        }).join('');

        if (append) body.insertAdjacentHTML('beforeend', html);
        else body.innerHTML = html;

        body.querySelectorAll('.lv-file-thumb').forEach(function (img) {
            if (img.dataset.boundErr) return;
            img.dataset.boundErr = '1';
            var fail = function () {
                var btn = img.closest('.lv-file-thumb-btn');
                img.remove();
                if (btn && !btn.querySelector('img')) {
                    btn.innerHTML = '<i class="fa fa-image"></i> View';
                    btn.classList.add('lv-file-fallback');
                }
            };
            img.addEventListener('error', fail);
            if (img.complete && img.naturalWidth === 0) fail();
        });
    }

    function buildLogRowHtml(r) {
        var isCredit = r.change_type === 'credit';
        var sign = isCredit ? '+' : '-';
        var actionCls = isCredit ? 'is-credit' : 'is-debit';
        var icon = isCredit ? 'plus' : 'minus';
        var actionLabel = r.change_type
            ? r.change_type.charAt(0).toUpperCase() + r.change_type.slice(1)
            : '';

        return '<tr>'
            + '<td class="lv-log-date">' + esc(r.updated_at) + '</td>'
            + '<td><span class="lv-log-action ' + actionCls + '">'
            + '<i class="fa fa-' + icon + '-circle" style="font-size:9px"></i>'
            + esc(actionLabel)
            + '</span></td>'
            + '<td class="lv-log-type">' + esc(r.leave_type) + '</td>'
            + '<td class="lv-log-days ' + actionCls + '">' + sign + esc(r.days_changed) + '</td>'
            + '<td class="lv-log-bal">' + esc(r.new_balance) + '</td>'
            + '<td class="lv-log-remark" title="' + esc(r.reason).replace(/"/g, '&quot;') + '">' + esc(r.reason) + '</td>'
            + '</tr>';
    }

    function renderLogRows(rows, append) {
        var body = document.getElementById('lvl-body');
        if (!body) return;

        if (!append) body.innerHTML = '';

        if (!rows.length && !append) {
            body.innerHTML = '<tr><td colspan="6" style="text-align:center;padding:24px;color:#94a3b8;font-size:13px">No leave logs found.</td></tr>';
            return;
        }

        var html = rows.map(buildLogRowHtml).join('');

        if (append) body.insertAdjacentHTML('beforeend', html);
        else body.innerHTML = html;
    }

    function logMatchesFilter(row, filterName) {
        if (!filterName) return true;
        var label = String(row.leave_type || '');
        var raw = String(row.leave_type_raw || '');
        var key = filterName.toLowerCase().replace(/\s+/g, '_') + '_limit';
        return label === filterName
            || raw === filterName
            || raw === key
            || label.toLowerCase() === filterName.toLowerCase();
    }

    function getFilteredLogs(filterName) {
        if (!logsIndex) return [];
        var key = logFilterKey(filterName);
        return logsIndex[key] || logsIndex[''] || [];
    }

    function filterLogsLocally(filterName) {
        if (!window.ED_LEAVE) window.ED_LEAVE = defaultLeaveState();
        filterName = logFilterKey(filterName);
        window.ED_LEAVE.logsFilter = filterName;
        logsVisibleCount = PAGE_SIZE;

        if (!logsMaster) {
            renderLogsSkeleton(4);
            ensureLogsMaster().then(function () {
                filterLogsLocally(filterName);
            });
            return;
        }

        var filtered = getFilteredLogs(filterName);
        var cacheKey = filterName + '|' + logsVisibleCount;
        var body = document.getElementById('lvl-body');

        if (logsHtmlCache[cacheKey] && body) {
            body.innerHTML = logsHtmlCache[cacheKey];
        } else {
            renderLogRows(filtered.slice(0, logsVisibleCount), false);
            if (body && filtered.length) {
                logsHtmlCache[cacheKey] = body.innerHTML;
            }
        }

        window.ED_LEAVE.hasMoreLogs = filtered.length > logsVisibleCount;
        window.ED_LEAVE.logs = filtered;
        setPaneLoading('lv-logs-scroll', false);
    }

    function loadMoreLogsLocal() {
        if (!logsMaster || !window.ED_LEAVE) return;
        var filterName = logFilterKey(window.ED_LEAVE.logsFilter || '');
        var filtered = getFilteredLogs(filterName);
        logsVisibleCount += PAGE_SIZE;
        renderLogRows(filtered.slice(logsVisibleCount - PAGE_SIZE, logsVisibleCount), true);
        window.ED_LEAVE.hasMoreLogs = filtered.length > logsVisibleCount;
    }

    function ensureLogsMaster() {
        if (logsMaster) return Promise.resolve(logsMaster);
        if (logsMasterPromise) return logsMasterPromise;

        logsMasterLoading = true;
        logsMasterPromise = fetchJson(apiUrl({ action: 'logs', bulk: 1 }))
            .then(function (data) {
                var block = (data && data.leave) || {};
                applyLogsBlock(block);
                return logsMaster;
            })
            .finally(function () {
                logsMasterLoading = false;
            });

        return logsMasterPromise;
    }

    function renderLogsFromServer(reset) {
        if (!window.ED_LEAVE) window.ED_LEAVE = defaultLeaveState();

        if (reset) {
            window.ED_LEAVE.logsOffset = 0;
            window.ED_LEAVE.hasMoreLogs = false;
        } else if (!window.ED_LEAVE.hasMoreLogs) {
            return Promise.resolve();
        }

        if (inflight.logs) inflight.logs.abort();
        var controller = new AbortController();
        inflight.logs = controller;

        var query = {
            action: 'logs',
            offset: window.ED_LEAVE.logsOffset || 0,
            limit: LOGS_PAGE_SIZE,
        };
        if (window.ED_LEAVE.logsFilter) query.leave_type = window.ED_LEAVE.logsFilter;

        return fetchJson(apiUrl(query), { signal: controller.signal })
            .then(function (data) {
                if (controller.signal.aborted) return;
                var block = (data && data.leave) || {};
                var rows = block.rows || [];
                window.ED_LEAVE.logsOffset = block.next_offset != null ? block.next_offset : (window.ED_LEAVE.logsOffset + rows.length);
                window.ED_LEAVE.hasMoreLogs = !!block.has_more;
                renderLogRows(rows, !reset);
            })
            .catch(function (err) {
                if (err && err.name === 'AbortError') return;
                var msg = mapFetchError(err, 'Unable to load leave logs.');
                if (reset && msg) {
                    renderLogRows([], false);
                    showToast(msg, 'error');
                }
            })
            .finally(function () {
                if (inflight.logs === controller) inflight.logs = null;
                setPaneLoading('lv-logs-scroll', false);
            });
    }

    function loadBootstrap(force, silent) {
        if (!force && (loading || loaded)) return Promise.resolve();

        if (inflight.bootstrap) inflight.bootstrap.abort();

        var controller = new AbortController();
        inflight.bootstrap = controller;
        loading = true;

        return fetchJson(apiUrl({ action: 'bootstrap' }), { signal: controller.signal })
            .then(function (data) {
                if (controller.signal.aborted) return;
                var block = (data && data.leave) || {};
                if (block.overview) applyOverview(block.overview);
                if (block.history) {
                    var key = historyCacheKey(block.history.date_from && block.history.date_to
                        ? { date_from: block.history.date_from, date_to: block.history.date_to }
                        : window.ED_LEAVE.historyFilters, 0);
                    historyCache[key] = block.history;
                    applyHistoryBlock(block.history, true);
                }
                if (block.logs) applyLogsBlock(block.logs);
            })
            .catch(function (err) {
                if (err && err.name === 'AbortError') return;
                var msg = mapFetchError(err, 'Unable to load leave data.');
                if (!silent && msg) {
                    showToast(msg, 'error');
                    if (!window.ED_LEAVE) window.ED_LEAVE = defaultLeaveState();
                    window.ED_LEAVE.error = msg;
                }
            })
            .finally(function () {
                if (inflight.bootstrap === controller) inflight.bootstrap = null;
                loading = false;
            });
    }

    function loadHistory(reset) {
        if (!window.ED_LEAVE) window.ED_LEAVE = defaultLeaveState();

        if (reset) {
            window.ED_LEAVE.historyOffset = 0;
            window.ED_LEAVE.history = [];
            window.ED_LEAVE.hasMoreHistory = false;
        } else if (!window.ED_LEAVE.hasMoreHistory && window.ED_LEAVE.historyLoaded) {
            return Promise.resolve();
        }

        var filters = window.ED_LEAVE.historyFilters || {};
        var offset = reset ? 0 : (window.ED_LEAVE.historyOffset || 0);
        var cacheKey = historyCacheKey(filters, offset);

        if (historyCache[cacheKey]) {
            applyHistoryBlock(historyCache[cacheKey], reset);
            return Promise.resolve();
        }

        if (historyLoading) return Promise.resolve();
        if (inflight.history) inflight.history.abort();

        var controller = new AbortController();
        inflight.history = controller;
        historyLoading = true;

        if (reset) {
            renderHistorySkeleton(6);
            setPaneLoading('lv-hist-scroll', true);
        }

        var query = {
            action: 'history',
            offset: offset,
            limit: PAGE_SIZE,
        };
        if (filters.date_from) query.date_from = filters.date_from;
        if (filters.date_to) query.date_to = filters.date_to;

        return fetchJson(apiUrl(query), { signal: controller.signal })
            .then(function (data) {
                if (controller.signal.aborted) return;
                var block = (data && data.leave) || {};
                historyCache[cacheKey] = block;
                applyHistoryBlock(block, reset);
            })
            .catch(function (err) {
                if (err && err.name === 'AbortError') return;
                var msg = mapFetchError(err, 'Unable to load leave history.');
                if (reset && msg) {
                    var body = document.getElementById('lvh-body');
                    if (body) {
                        body.innerHTML = '<tr><td colspan="8" style="text-align:center;padding:24px;color:#ef4444;font-size:13px">' + esc(msg) + '</td></tr>';
                    }
                    showToast(msg, 'error');
                }
                setPaneLoading('lv-hist-scroll', false);
            })
            .finally(function () {
                if (inflight.history === controller) inflight.history = null;
                historyLoading = false;
            });
    }

    function setupInfiniteScroll(containerId, sentinelId, loaderFn) {
        var container = document.getElementById(containerId);
        var sentinel = document.getElementById(sentinelId);
        if (!container || !sentinel || typeof IntersectionObserver === 'undefined') return;

        new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (entry.isIntersecting) loaderFn();
            });
        }, { root: container, rootMargin: '200px', threshold: 0 }).observe(sentinel);
    }

    function subApiUrl(query) {
        if (typeof window.edApiUrl === 'function') {
            return window.edApiUrl('leave-substitutes', query);
        }
        var base = (window.ED_CONFIG && window.ED_CONFIG.apiBase) || '/api/employee';
        var url = base.replace(/\/$/, '') + '/leave-substitutes.php';
        if (query) {
            url += (url.indexOf('?') >= 0 ? '&' : '?') + new URLSearchParams(query).toString();
        }
        return url;
    }

    function clearSubstituteState(preserveMap) {
        pendingSubstitutes = [];
        busySessionsCache = null;
        busySessionsRangeKey = '';
        substitutesConfirmedForRange = '';
        scheduleLoadState = 'idle';
        scheduleLoadError = '';
        if (!preserveMap) confirmedSubstitutesByRange = {};
        Object.keys(priorSelects).forEach(function (k) {
            if (priorSelects[k] && typeof priorSelects[k].clear === 'function') {
                priorSelects[k].clear();
            }
        });
        priorSelects = {};
        setPastDateNote(false);
        var badge = document.getElementById('lv-sub-status');
        if (badge) {
            badge.style.display = 'none';
            badge.textContent = '';
            badge.removeAttribute('role');
            badge.removeAttribute('tabindex');
            badge.removeAttribute('title');
        }
    }

    function updateSubstituteBadge(count, rangeLabel) {
        var badge = document.getElementById('lv-sub-status');
        if (!badge) return;
        if (!count || !rangeLabel) {
            badge.style.display = 'none';
            badge.textContent = '';
            return;
        }
        badge.style.display = 'block';
        badge.setAttribute('role', 'button');
        badge.setAttribute('tabindex', '0');
        badge.setAttribute('title', 'Click to review or change assigned substitutes');
        badge.innerHTML = esc(String(count)) + ' substitute(s) assigned for ' + esc(String(rangeLabel))
            + ' <span style="font-weight:500;opacity:.85">· click to edit</span>';
    }

    function findPendingPickForSession(session) {
        var key = sessionPickKey(session);
        for (var i = 0; i < pendingSubstitutes.length; i++) {
            if (sessionPickKey(pendingSubstitutes[i]) === key) {
                return pendingSubstitutes[i];
            }
        }
        return null;
    }

    function facultyOptionLabel(f) {
        if (!f) return '';
        return f.label || ((f.employee_name || '') + (f.bio_id ? (' (' + f.bio_id + ')') : ''));
    }

    function findFacultyLabel(faculty, bioId, fallbackName) {
        var bio = String(bioId || '').trim();
        if (!bio) return '';
        for (var i = 0; i < (faculty || []).length; i++) {
            if (String(faculty[i].bio_id || '').trim() === bio) {
                return facultyOptionLabel(faculty[i]);
            }
        }
        if (fallbackName) return String(fallbackName) + ' (' + bio + ')';
        return bio;
    }

    function preselectPriorFaculty(ss, nativeSel, faculty, bioId, fallbackName) {
        var bio = String(bioId || '').trim();
        if (!bio) return;
        var label = findFacultyLabel(faculty, bio, fallbackName);

        if (ss && typeof ss.selectValue === 'function') {
            ss.value = bio;
            if (ss.hidden) ss.hidden.value = bio;
            if (ss.labelEl) {
                ss.labelEl.textContent = label;
                ss.labelEl.classList.remove('is-placeholder');
            }
            if (ss.nativeSelect) {
                var hasOpt = false;
                Array.prototype.forEach.call(ss.nativeSelect.options || [], function (opt) {
                    if (String(opt.value).trim() === bio) hasOpt = true;
                });
                if (!hasOpt) {
                    var opt = document.createElement('option');
                    opt.value = bio;
                    opt.textContent = label;
                    ss.nativeSelect.appendChild(opt);
                }
                ss.nativeSelect.value = bio;
            }
            if (typeof ss.renderList === 'function') ss.renderList();
            return;
        }

        if (nativeSel) {
            var found = false;
            Array.prototype.forEach.call(nativeSel.options || [], function (opt) {
                if (String(opt.value).trim() === bio) found = true;
            });
            if (!found) {
                var o = document.createElement('option');
                o.value = bio;
                o.textContent = label;
                nativeSel.appendChild(o);
            }
            nativeSel.value = bio;
        }
    }

    function reopenPriorSubstitutesModal() {
        var range = getLeaveDateRange();
        if (!range.from || !range.to) {
            showToast('Please choose from and to dates first.', 'warning');
            return;
        }
        var key = leaveRangeKey(range.from, range.to);
        if (busySessionsCache && busySessionsRangeKey === key
            && (busySessionsCache.sessions || []).length) {
            openPriorModal(
                busySessionsCache.sessions,
                busySessionsCache.total != null ? busySessionsCache.total : busySessionsCache.sessions.length
            );
            return;
        }
        onLeaveDateChanged();
    }

    function loadFacultyOptions() {
        if (facultyOptionsCache) return Promise.resolve(facultyOptionsCache);
        return fetch(subApiUrl({ action: 'faculty-options' }), {
            credentials: 'same-origin',
            headers: { Accept: 'application/json' },
        }).then(function (res) {
            return res.json().then(function (json) {
                if (!json || !json.success) throw new Error((json && json.error) || 'Failed to load faculty list.');
                facultyOptionsCache = (json.data && json.data.faculty) || [];
                return facultyOptionsCache;
            });
        });
    }

    function fetchBusySessions(fromDate, toDate) {
        if (busyAbort) {
            try { busyAbort.abort(); } catch (e) { /* ignore */ }
        }
        busyAbort = typeof AbortController !== 'undefined' ? new AbortController() : null;
        var opts = {
            credentials: 'same-origin',
            headers: { Accept: 'application/json' },
        };
        if (busyAbort) opts.signal = busyAbort.signal;

        return fetch(subApiUrl({
            action: 'busy-sessions',
            from_date: fromDate,
            to_date: toDate,
            leave_date: fromDate,
        }), opts)
            .then(function (res) {
                return res.json().then(function (json) {
                    if (!json || !json.success) {
                        throw new Error((json && json.error) || 'Failed to load prior engagements.');
                    }
                    return json.data && json.data.busy_sessions ? json.data.busy_sessions : { total: 0, sessions: [] };
                });
            });
    }

    function onLeaveDateChanged() {
        updateDaysHint();
        if (!isTeaching && !(window.ED_LEAVE && window.ED_LEAVE.is_teaching)) {
            clearSubstituteState();
            syncSubmitButton();
            return;
        }
        isTeaching = true;
        var range = getLeaveDateRange();
        clearSubstituteState(true);
        if (!range.from || !range.to) {
            syncSubmitButton();
            return;
        }
        if (range.from > range.to) {
            showToast('From date cannot be after To date.', 'warning');
            syncSubmitButton();
            return;
        }

        var key = leaveRangeKey(range.from, range.to);
        var rangeLabel = range.from === range.to ? range.from : (range.from + ' – ' + range.to);

        // Past calendar range: normal leave flow, no substitute modal / schedule API.
        if (isRetroactiveLeaveRange(range.from, range.to)) {
            scheduleLoadState = 'ready';
            substitutesConfirmedForRange = key;
            pendingSubstitutes = [];
            setPastDateNote(true);
            if (priorModal) {
                try { priorModal.hide(); } catch (e) { /* ignore */ }
            }
            syncSubmitButton();
            return;
        }
        setPastDateNote(false);

        // Restore previously confirmed picks for this range if present.
        if (confirmedSubstitutesByRange[key]) {
            pendingSubstitutes = confirmedSubstitutesByRange[key].slice();
            substitutesConfirmedForRange = key;
            updateSubstituteBadge(pendingSubstitutes.length, rangeLabel);
        }

        if (busyDebounceTimer) clearTimeout(busyDebounceTimer);
        busyDebounceTimer = setTimeout(function () {
            var requestedKey = key;
            var requestedFrom = range.from;
            var requestedTo = range.to;
            var loading = document.getElementById('lv-prior-loading');
            if (loading) loading.style.display = 'block';
            scheduleLoadState = 'loading';
            scheduleLoadError = '';
            syncSubmitButton();
            fetchBusySessions(requestedFrom, requestedTo)
                .then(function (data) {
                    var current = getLeaveDateRange();
                    if (leaveRangeKey(current.from, current.to) !== requestedKey) return;
                    if (isRetroactiveLeaveRange(current.from, current.to)) {
                        scheduleLoadState = 'ready';
                        setPastDateNote(true);
                        syncSubmitButton();
                        return;
                    }
                    busySessionsCache = data;
                    busySessionsRangeKey = requestedKey;
                    scheduleLoadState = 'ready';
                    var sessions = filterSessionsForDayType(data.sessions || []);
                    var total = sessions.length;
                    var label = requestedFrom === requestedTo
                        ? requestedFrom
                        : (requestedFrom + ' – ' + requestedTo);
                    if (total > 0) {
                        if (substitutesConfirmedForRange === requestedKey && pendingSubstitutes.length) {
                            updateSubstituteBadge(pendingSubstitutes.length, label);
                        } else {
                            openPriorModal(sessions, total);
                        }
                    } else {
                        substitutesConfirmedForRange = requestedKey;
                        pendingSubstitutes = [];
                        confirmedSubstitutesByRange[requestedKey] = [];
                        updateSubstituteBadge(0, '');
                    }
                    syncSubmitButton();
                })
                .catch(function (err) {
                    if (err && err.name === 'AbortError') return;
                    var current = getLeaveDateRange();
                    if (leaveRangeKey(current.from, current.to) !== requestedKey) return;
                    scheduleLoadState = 'error';
                    scheduleLoadError = mapFetchError(err, 'Could not load schedule for these dates.') || 'Could not load schedule for these dates.';
                    showToast(scheduleLoadError, 'warning');
                    syncSubmitButton();
                })
                .finally(function () {
                    if (loading) loading.style.display = 'none';
                    syncSubmitButton();
                });
        }, 350);
        syncSubmitButton();
    }

    function teachingNeedsSubstitutes() {
        if (!isTeaching && !(window.ED_LEAVE && window.ED_LEAVE.is_teaching)) return false;
        var range = getLeaveDateRange();
        if (!range.from || !range.to || isRetroactiveLeaveRange(range.from, range.to)) return false;
        if (scheduleLoadState === 'loading' || scheduleLoadState === 'error') return true;
        var key = leaveRangeKey(range.from, range.to);
        if (busySessionsRangeKey === key && busySessionsCache) {
            var sessions = filterSessionsForDayType(busySessionsCache.sessions || []);
            return sessions.length > 0;
        }
        return scheduleLoadState !== 'ready';
    }

    function validateForm() {
        var category = (document.getElementById('lv-type') || {}).value || '';
        var range = getLeaveDateRange();
        var reason = ((document.getElementById('lv-reason') || {}).value || '').trim();
        var session = (document.getElementById('lv-sess') || {}).value || '';
        var fileInput = document.getElementById('lv-file');
        var file = fileInput && fileInput.files ? fileInput.files[0] : null;
        var rangeKey = leaveRangeKey(range.from, range.to);

        if (!category) return { ok: false, message: 'Please select a leave type.' };
        if (!range.from || !range.to) return { ok: false, message: 'Please choose from and to leave dates.' };
        if (range.from > range.to) return { ok: false, message: 'From date cannot be after To date.' };
        if (dayType === 'half' && range.from !== range.to) {
            return { ok: false, message: 'Half day leave is only allowed when From and To are the same date.' };
        }

        var requestedDays = getRequestedLeaveDays(range.from, range.to);
        var balance = getSelectedLeaveBalance();
        var shortfall = getBalanceShortfallMessage(requestedDays, balance);
        if (shortfall) {
            return { ok: false, message: shortfall };
        }

        if (!reason) return { ok: false, message: 'Please enter a reason for leave.' };
        if (dayType === 'half' && !session) return { ok: false, message: 'Please select Session-1 or Session-2 for half day leave.' };
        if (!file) return { ok: false, message: 'Supporting image is required.' };
        if (file.size > 10 * 1024 * 1024) return { ok: false, message: 'Image must be 10 MB or smaller.' };
        if (!/^image\/(jpeg|png)$/i.test(file.type)) return { ok: false, message: 'Only PNG and JPEG images are allowed.' };

        if ((isTeaching || (window.ED_LEAVE && window.ED_LEAVE.is_teaching)) && !isRetroactiveLeaveRange(range.from, range.to)) {
            if (scheduleLoadState === 'loading') {
                return { ok: false, message: 'Loading your schedule for these dates. Please wait…', needPrior: false };
            }
            if (scheduleLoadState === 'error') {
                return {
                    ok: false,
                    message: scheduleLoadError || 'Could not load schedule for these dates. Please change the dates or try again.',
                    needPrior: true,
                };
            }
            if (scheduleLoadState === 'idle') {
                return { ok: false, message: 'Please wait while we check your schedule for these dates.', needPrior: true };
            }
            if (teachingNeedsSubstitutes()) {
                if (substitutesConfirmedForRange !== rangeKey || !pendingSubstitutes.length) {
                    return {
                        ok: false,
                        message: 'Please assign substitutes for your prior engagements before submitting.',
                        needPrior: true,
                    };
                }
            }
        }

        return {
            ok: true,
            category: category,
            fromDate: range.from,
            toDate: range.to,
            leaveDate: range.from,
            reason: reason,
            session: session,
            file: file,
        };
    }

    function openPriorModal(sessions, total) {
        var modalEl = document.getElementById('lv-prior-modal');
        var list = document.getElementById('lv-prior-sessions');
        var countEl = document.getElementById('lv-prior-count');
        if (!modalEl || !list) return;

        sessions = filterSessionsForDayType(sessions || []);
        if (countEl) countEl.textContent = String(total || sessions.length) + ' scheduled';
        priorSelects = {};

        loadFacultyOptions().then(function (faculty) {
            list.innerHTML = sessions.map(function (s, idx) {
                var title = (s.course_code ? s.course_code + ': ' : '') + (s.course_name || s.offering_code || 'Session');
                var time = [s.start_time, s.end_time].filter(Boolean).join(' - ') || '—';
                var sessDate = sessionDateOf(s);
                var selId = 'lv-prior-sel-' + idx;
                var prior = findPendingPickForSession(s);
                var priorHint = prior
                    ? '<div class="lv-prior-card-meta" style="color:#0369a1">Previously selected — change if needed</div>'
                    : '';
                return '<div class="lv-prior-card" data-idx="' + idx + '">'
                    + '<div class="lv-prior-card-info">'
                    + '<div class="lv-prior-card-title"><i class="fa fa-book"></i> ' + esc(title) + '</div>'
                    + (sessDate ? '<div class="lv-prior-card-meta"><i class="fa fa-calendar-day"></i> ' + esc(formatLeaveDate(sessDate)) + '</div>' : '')
                    + '<div class="lv-prior-card-meta"><i class="fa fa-clock"></i> ' + esc(time) + '</div>'
                    + (s.offering_code ? '<div class="lv-prior-card-meta"><i class="fa fa-hashtag"></i> ' + esc(s.offering_code) + '</div>' : '')
                    + priorHint
                    + '</div>'
                    + '<div class="lv-prior-card-assign">'
                    + '<label class="flbl" for="' + selId + '">Assign Substitute</label>'
                    + '<select class="finp lv-prior-faculty-sel" id="' + selId + '" data-idx="' + idx + '">'
                    + '<option value="">Select faculty...</option>'
                    + '</select>'
                    + '</div></div>';
            }).join('');

            sessions.forEach(function (s, idx) {
                var sel = document.getElementById('lv-prior-sel-' + idx);
                if (!sel) return;
                var options = (faculty || []).map(function (f) {
                    return { value: String(f.bio_id || ''), label: facultyOptionLabel(f) };
                }).filter(function (o) { return o.value; });
                var prior = findPendingPickForSession(s);
                var priorBio = prior ? String(prior.substitute_bio_id || '').trim() : '';
                var priorName = prior ? String(prior.substitute_name || '') : '';

                // Ensure previously selected faculty remains in the option list.
                if (priorBio && !options.some(function (o) { return o.value === priorBio; })) {
                    options.unshift({
                        value: priorBio,
                        label: findFacultyLabel(faculty, priorBio, priorName),
                    });
                }

                if (window.EDSearchSelect) {
                    priorSelects[idx] = new EDSearchSelect(sel, {
                        placeholder: 'Select faculty...',
                        triggerClass: 'pr-ss-trigger finp',
                        onSearch: function (q, done) {
                            fetch(subApiUrl({ action: 'faculty-options', q: q }), {
                                credentials: 'same-origin',
                                headers: { Accept: 'application/json' },
                            }).then(function (res) {
                                return res.json();
                            }).then(function (json) {
                                if (json && json.success && json.data && json.data.faculty) {
                                    var list = json.data.faculty.map(function (f) {
                                        return { value: String(f.bio_id || ''), label: facultyOptionLabel(f) };
                                    });
                                    done(list);
                                } else {
                                    done([]);
                                }
                            }).catch(function () {
                                done([]);
                            });
                        },
                    });
                    priorSelects[idx].setOptions(options);
                    if (priorBio) {
                        preselectPriorFaculty(priorSelects[idx], sel, faculty, priorBio, priorName);
                    }
                } else {
                    options.forEach(function (f) {
                        var opt = document.createElement('option');
                        opt.value = f.value;
                        opt.textContent = f.label;
                        if (priorBio && f.value === priorBio) opt.selected = true;
                        sel.appendChild(opt);
                    });
                    if (priorBio) {
                        preselectPriorFaculty(null, sel, faculty, priorBio, priorName);
                    }
                }
            });

            if (typeof bootstrap !== 'undefined') {
                if (!priorModal) priorModal = new bootstrap.Modal(modalEl);
                priorModal.show();
            }
        }).catch(function (err) {
            showToast(mapFetchError(err, 'Failed to load faculty list.') || 'Failed to load faculty list.', 'error');
        });
    }

    function confirmPriorSubstitutes() {
        var sessions = filterSessionsForDayType((busySessionsCache && busySessionsCache.sessions) || []);
        if (!sessions.length) {
            if (priorModal) priorModal.hide();
            return;
        }

        var faculty = facultyOptionsCache || [];
        var picks = [];
        for (var i = 0; i < sessions.length; i++) {
            var s = sessions[i];
            var bio = '';
            if (priorSelects[i] && typeof priorSelects[i].getValue === 'function') {
                bio = String(priorSelects[i].getValue() || '').trim();
            } else {
                var sel = document.getElementById('lv-prior-sel-' + i);
                bio = sel ? String(sel.value || '').trim() : '';
            }
            if (!bio) {
                showToast('Please assign a substitute for every session.', 'warning');
                return;
            }
            if (!courseKeyFromSession(s)) {
                showToast('A scheduled session is missing course identity. Please contact administration.', 'error');
                return;
            }
            picks.push({
                substitute_bio_id: bio,
                substitute_name: findFacultyLabel(faculty, bio, '').replace(/\s*\([^)]*\)\s*$/, ''),
                session_date: sessionDateOf(s) || getLeaveDateRange().from,
                session_number: s.session_number,
                start_time: s.start_time || '',
                end_time: s.end_time || '',
                start_minutes: s.start_minutes != null ? s.start_minutes : 0,
                end_minutes: s.end_minutes != null ? s.end_minutes : 0,
                offering_code: s.offering_code || '',
                course_code: s.course_code || '',
                course_name: s.course_name || '',
            });
        }

        pendingSubstitutes = picks;
        substitutesConfirmedForRange = busySessionsRangeKey;
        confirmedSubstitutesByRange[busySessionsRangeKey] = picks.slice();
        var rangeParts = String(busySessionsRangeKey || '').split('|');
        var badgeLabel = rangeParts.length === 2 && rangeParts[0] && rangeParts[1]
            ? (rangeParts[0] === rangeParts[1] ? rangeParts[0] : (rangeParts[0] + ' – ' + rangeParts[1]))
            : busySessionsRangeKey;
        updateSubstituteBadge(picks.length, badgeLabel);
        if (priorModal) priorModal.hide();
        showToast('Substitutes saved. Click the status below to review or change them.', 'success');
        syncSubmitButton();
    }

    function resetForm() {
        ['lv-reason', 'lv-date-from', 'lv-date-to', 'lv-type', 'lv-sess'].forEach(function (id) {
            var el = document.getElementById(id);
            if (el) el.value = '';
        });
        var hint = document.getElementById('lv-days-hint');
        if (hint) {
            hint.style.display = 'none';
            hint.textContent = '';
            hint.style.color = '';
            hint.style.background = '';
            hint.style.borderColor = '';
        }
        var fileInput = document.getElementById('lv-file');
        var fileZone = document.getElementById('lv-file-zone');
        var fileLabel = document.getElementById('lv-file-label');
        if (fileInput) fileInput.value = '';
        if (fileZone) fileZone.classList.remove('has-file');
        if (fileLabel) fileLabel.textContent = 'No file selected';
        setDayType('full');
        clearSubstituteState();
        syncSubmitButton();
    }

    function bindScrolls() {
        if (scrollBound || !document.getElementById('lv-hist-scroll')) return;
        scrollBound = true;
        setupInfiniteScroll('lv-hist-scroll', 'lv-hist-sentinel', function () {
            if (window.ED_LEAVE && window.ED_LEAVE.hasMoreHistory && !historyLoading) {
                loadHistory(false);
            }
        });
        setupInfiniteScroll('lv-logs-scroll', 'lv-logs-sentinel', function () {
            if (!window.ED_LEAVE) return;
            if (logsMaster) {
                if (window.ED_LEAVE.hasMoreLogs) loadMoreLogsLocal();
                return;
            }
            if (!logsMasterLoading) renderLogsFromServer(false);
        });
    }

    function bindFormEvents() {
        if (formsBound || !document.getElementById('lv-hist-apply')) return;
        formsBound = true;
        initFileModal();

        var fileInput = document.getElementById('lv-file');
        var fileZone = document.getElementById('lv-file-zone');
        var fileLabel = document.getElementById('lv-file-label');

        if (fileInput && fileZone) {
            fileZone.addEventListener('dragover', function (e) { e.preventDefault(); fileZone.classList.add('has-file'); });
            fileZone.addEventListener('dragleave', function () {
                if (!fileInput.files || !fileInput.files.length) fileZone.classList.remove('has-file');
            });
            fileZone.addEventListener('drop', function (e) {
                e.preventDefault();
                if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]) {
                    fileInput.files = e.dataTransfer.files;
                    fileInput.dispatchEvent(new Event('change'));
                }
            });
            fileInput.addEventListener('change', function () {
                var file = fileInput.files && fileInput.files[0];
                if (!file) {
                    fileZone.classList.remove('has-file');
                    if (fileLabel) fileLabel.textContent = 'No file selected';
                    syncSubmitButton();
                    return;
                }
                if (file.size > 10 * 1024 * 1024) {
                    showToast('Image must be 10 MB or smaller.', 'warning');
                    fileInput.value = '';
                    fileZone.classList.remove('has-file');
                    if (fileLabel) fileLabel.textContent = 'No file selected';
                    syncSubmitButton();
                    return;
                }
                if (!/^image\/(jpeg|png)$/i.test(file.type)) {
                    showToast('Only PNG and JPEG images are allowed.', 'warning');
                    fileInput.value = '';
                    fileZone.classList.remove('has-file');
                    if (fileLabel) fileLabel.textContent = 'No file selected';
                    syncSubmitButton();
                    return;
                }
                fileZone.classList.add('has-file');
                if (fileLabel) fileLabel.textContent = file.name + ' (' + (file.size / (1024 * 1024)).toFixed(2) + ' MB)';
                syncSubmitButton();
            });
        }

        document.getElementById('lv-reason')?.addEventListener('input', syncSubmitButton);
        document.getElementById('lv-reason')?.addEventListener('change', syncSubmitButton);

        document.getElementById('lv-hist-apply')?.addEventListener('click', function () {
            var fromEl = document.getElementById('lv-hist-from');
            var toEl = document.getElementById('lv-hist-to');
            if (!window.ED_LEAVE) window.ED_LEAVE = defaultLeaveState();
            window.ED_LEAVE.historyFilters.date_from = fromEl ? fromEl.value : '';
            window.ED_LEAVE.historyFilters.date_to = toEl ? toEl.value : '';
            if (window.ED_LEAVE.historyFilters.date_from && window.ED_LEAVE.historyFilters.date_to
                && window.ED_LEAVE.historyFilters.date_from > window.ED_LEAVE.historyFilters.date_to) {
                showToast('From date cannot be after To date.', 'warning');
                return;
            }
            window.ED_LEAVE.historyLoaded = false;
            loadHistory(true);
        });

        document.getElementById('lv-hist-reset')?.addEventListener('click', function () {
            if (!window.ED_LEAVE || !window.ED_LEAVE.cycle) return;
            window.ED_LEAVE.historyFilters.date_from = window.ED_LEAVE.cycle.date_from || '';
            window.ED_LEAVE.historyFilters.date_to = window.ED_LEAVE.cycle.date_to || '';
            syncHistoryFilterInputs();
            window.ED_LEAVE.historyLoaded = false;
            loadHistory(true);
        });

        document.getElementById('lv-log-filter')?.addEventListener('change', function (e) {
            filterLogsLocally(e.target.value || '');
        });

        document.getElementById('lvh-body')?.addEventListener('click', function (e) {
            var fileBtn = e.target.closest('.lv-file-thumb-btn');
            if (fileBtn) {
                openFileViewer(fileBtn.getAttribute('data-file-url') || '');
                return;
            }
            var btn = e.target.closest('.lv-cancel-btn');
            if (!btn) return;
            openCancelModal(parseInt(btn.getAttribute('data-leave-id'), 10));
        });

        document.getElementById('lv-cancel-confirm')?.addEventListener('click', function () {
            if (pendingCancelId) confirmCancelLeave(pendingCancelId);
        });

        document.getElementById('lv-date-from')?.addEventListener('change', onLeaveDateChanged);
        document.getElementById('lv-date-to')?.addEventListener('change', onLeaveDateChanged);
        document.getElementById('lv-type')?.addEventListener('change', function () {
            updateDaysHint();
        });
        document.getElementById('lv-sess')?.addEventListener('change', function () {
            if (dayType === 'half') setDayType('half');
            else syncSubmitButton();
        });
        document.getElementById('lv-prior-confirm')?.addEventListener('click', confirmPriorSubstitutes);
        document.getElementById('lv-sub-status')?.addEventListener('click', function () {
            reopenPriorSubstitutesModal();
        });
        document.getElementById('lv-sub-status')?.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                reopenPriorSubstitutesModal();
            }
        });
        syncSubmitButton();
    }

    function initFileModal() {
        if (document.getElementById('lv-file-modal')) return;
        var wrap = document.createElement('div');
        wrap.innerHTML = '<div class="modal fade" id="lv-file-modal" tabindex="-1" aria-hidden="true">'
            + '<div class="modal-dialog modal-dialog-centered modal-lg">'
            + '<div class="modal-content lv-file-modal-content">'
            + '<div class="modal-header lv-file-modal-header">'
            + '<h5 class="modal-title">Leave Attachment</h5>'
            + '<button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>'
            + '</div>'
            + '<div class="modal-body lv-file-modal-body">'
            + '<img id="lv-file-modal-img" src="" alt="Leave attachment">'
            + '</div></div></div></div>';
        document.body.appendChild(wrap.firstElementChild || wrap.firstChild);
    }

    function openFileViewer(url) {
        if (!url) return;
        initFileModal();
        var modalEl = document.getElementById('lv-file-modal');
        var imgEl = document.getElementById('lv-file-modal-img');
        if (!modalEl || !imgEl) {
            window.open(url, '_blank', 'noopener');
            return;
        }
        imgEl.src = url;
        if (typeof bootstrap === 'undefined') {
            window.open(url, '_blank', 'noopener');
            return;
        }
        var modal = bootstrap.Modal.getInstance(modalEl) || new bootstrap.Modal(modalEl);
        modal.show();
    }

    function openCancelModal(leaveId) {
        if (!leaveId || cancelBusy) return;
        pendingCancelId = leaveId;
        var modalEl = document.getElementById('lv-cancel-modal');
        if (!modalEl || typeof bootstrap === 'undefined') return;
        if (!cancelModal) cancelModal = new bootstrap.Modal(modalEl);
        cancelModal.show();
    }

    function schedulePrefetch() {
        if (prefetchStarted || loaded || loading) return;
        prefetchStarted = true;

        var run = function () { loadBootstrap(false, true); };
        if (typeof window.requestIdleCallback === 'function') {
            window.requestIdleCallback(run, { timeout: 1500 });
        } else {
            setTimeout(run, 200);
        }
    }

    function setDayType(type) {
        dayType = type === 'half' ? 'half' : 'full';
        var dtf = document.getElementById('dtf');
        var dth = document.getElementById('dth');
        var ind = document.getElementById('dt-ind');
        var sessRow = document.getElementById('sess-row');

        if (dtf) dtf.classList.toggle('active', dayType === 'full');
        if (dth) dth.classList.toggle('active', dayType === 'half');
        if (ind) ind.style.left = dayType === 'full' ? '3px' : 'calc(50%)';
        if (sessRow) sessRow.style.display = dayType === 'half' ? 'block' : 'none';

        // Half-day session filter changes which busy sessions must be covered.
        updateDaysHint();
        var range = getLeaveDateRange();
        var key = leaveRangeKey(range.from, range.to);
        if (range.from && range.to && range.from === range.to
            && !isRetroactiveLeaveRange(range.from, range.to)
            && busySessionsCache && busySessionsRangeKey === key) {
            substitutesConfirmedForRange = '';
            pendingSubstitutes = [];
            updateSubstituteBadge(0, '');
            var sessions = filterSessionsForDayType(busySessionsCache.sessions || []);
            if (sessions.length) {
                openPriorModal(sessions, sessions.length);
            }
        } else if (dayType === 'half' && range.from && range.to && range.from !== range.to) {
            showToast('Half day leave requires From and To to be the same date.', 'warning');
        }
        syncSubmitButton();
    }

    function submitApplication() {
        if (submitBusy) return Promise.resolve();

        var validation = validateForm();
        if (!validation.ok) {
            showToast(validation.message, 'warning');
            if (validation.needPrior && busySessionsCache && (busySessionsCache.sessions || []).length) {
                openPriorModal(busySessionsCache.sessions, busySessionsCache.total);
            } else if (validation.needPrior) {
                onLeaveDateChanged();
            }
            return Promise.resolve();
        }

        submitBusy = true;
        var submitBtn = document.getElementById('lv-submit-btn');
        if (submitBtn) {
            submitBtn.disabled = true;
            submitBtn.textContent = 'Submitting...';
        }

        var rangeKey = leaveRangeKey(validation.fromDate, validation.toDate);
        var fd = new FormData();
        fd.append('category', validation.category);
        fd.append('from_date', validation.fromDate);
        fd.append('to_date', validation.toDate);
        fd.append('leave_date', validation.fromDate);
        fd.append('leave_type', dayType === 'half' ? 'half_day' : 'full_day');
        if (dayType === 'half') fd.append('half_day_session', validation.session);
        fd.append('reason', validation.reason);
        fd.append('file', validation.file);
        fd.append('source', 'web');
        if (!isRetroactiveLeaveRange(validation.fromDate, validation.toDate)
            && pendingSubstitutes && pendingSubstitutes.length
            && substitutesConfirmedForRange === rangeKey) {
            fd.append('substitutes', JSON.stringify(pendingSubstitutes));
        }

        return fetch(apiUrl({ action: 'submit' }), {
            method: 'POST',
            credentials: 'same-origin',
            headers: { Accept: 'application/json' },
            body: fd,
        }).then(function (res) {
            return res.text().then(function (text) {
                var json = null;
                try { json = text ? JSON.parse(text) : null; } catch (e) { json = null; }
                if (!json || !json.success) {
                    var err = new Error((json && json.error) || 'Submission failed.');
                    err.code = json && json.code;
                    err.status = res.status;
                    throw err;
                }
                return json.data;
            });
        }).then(function (data) {
            showToast((data.leave && data.leave.message) || 'Leave application submitted successfully.', 'success');
            if (data.leave && data.leave.patch) applyPatch(data.leave.patch);
            resetForm();
        }).catch(function (err) {
            var msg = mapFetchError(err, 'Failed to submit leave application.');
            if (msg) showToast(msg, 'error');
        }).finally(function () {
            submitBusy = false;
            syncSubmitButton();
        });
    }

    function confirmCancelLeave(leaveId) {
        if (!leaveId || cancelBusy) return;

        cancelBusy = true;
        var confirmBtn = document.getElementById('lv-cancel-confirm');
        if (confirmBtn) {
            confirmBtn.disabled = true;
            confirmBtn.textContent = 'Cancelling...';
        }

        return fetchJson(apiUrl({ action: 'cancel' }), {
            method: 'POST',
            headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
            body: JSON.stringify({ leave_id: leaveId }),
        }).then(function (data) {
            if (cancelModal) cancelModal.hide();
            pendingCancelId = null;
            showToast((data.leave && data.leave.message) || 'Leave cancelled successfully.', 'success');
            if (data.leave && data.leave.patch) applyPatch(data.leave.patch);
        }).catch(function (err) {
            var msg = mapFetchError(err, 'Failed to cancel leave.');
            if (msg) showToast(msg, 'error');
        }).finally(function () {
            cancelBusy = false;
            if (confirmBtn) {
                confirmBtn.disabled = false;
                confirmBtn.textContent = 'Cancel Leave';
            }
        });
    }

    function onLogsTab() {
        var filterVal = (document.getElementById('lv-log-filter') || {}).value || '';

        if (logsMaster) {
            filterLogsLocally(filterVal);
            return Promise.resolve();
        }

        renderLogsSkeleton(4);

        return ensureLogsMaster()
            .then(function () {
                filterLogsLocally(filterVal);
            })
            .catch(function (err) {
                var msg = mapFetchError(err, 'Unable to load leave logs.');
                if (msg) showToast(msg, 'error');
                return renderLogsFromServer(true);
            });
    }

    window.EDLeave = {
        init: function () {
            window.ED_LEAVE = defaultLeaveState();
            bindFormEvents();
            bindScrolls();
        },
        ensureLoaded: function () {
            if (!window.ED_LEAVE) this.init();
            bindFormEvents();
            bindScrolls();

            if (loaded) {
                renderBalances();
                populateLeaveSelect();
                populateLogFilter();
                updateHeaderLeaveBalance(window.ED_LEAVE.balances || []);
                syncHistoryFilterInputs();
                if (window.ED_LEAVE.historyLoaded && document.getElementById('lvh-body')) {
                    renderHistoryRows(window.ED_LEAVE.history || [], false);
                }
                if (window.ED_LEAVE.historyLoaded) {
                    return Promise.resolve();
                }
            }

            if (!loaded) {
                renderHistorySkeleton(6);
            }

            return loadBootstrap(true, false).then(function () {
                syncHistoryFilterInputs();
                renderBalances();
                populateLeaveSelect();
                populateLogFilter();
                updateHeaderLeaveBalance((window.ED_LEAVE && window.ED_LEAVE.balances) || []);
                if (window.ED_LEAVE && window.ED_LEAVE.historyLoaded && document.getElementById('lvh-body')) {
                    renderHistoryRows(window.ED_LEAVE.history || [], false);
                }
            });
        },
        onLogsTab: onLogsTab,
        submitApplication: submitApplication,
        cancelLeave: openCancelModal,
        setDayType: setDayType,
        toast: showToast,
        prefetch: schedulePrefetch,
    };

    window.cancelLeave = function (id) {
        if (window.EDLeave) window.EDLeave.cancelLeave(id);
    };
})();