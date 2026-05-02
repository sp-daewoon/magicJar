// magicJar 공통 유틸. 번들러 없이 직접 로드. 전역 네임스페이스: window.MagicJar.
(function (global) {
    'use strict';

    const TZ = 'Asia/Seoul';

    const krwFormatter = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 });
    const pctFormatter = new Intl.NumberFormat('ko-KR', {
        style: 'percent', minimumFractionDigits: 2, maximumFractionDigits: 2,
    });
    const kstFormatter = new Intl.DateTimeFormat('ko-KR', {
        timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
    });

    function formatKrw(value) {
        if (value == null || Number.isNaN(Number(value))) return '-';
        return krwFormatter.format(Number(value)) + '원';
    }

    function formatPct(value) {
        if (value == null || Number.isNaN(Number(value))) return '-';
        return pctFormatter.format(Number(value));
    }

    function formatKst(isoString) {
        if (!isoString) return '-';
        const d = new Date(isoString);
        if (Number.isNaN(d.getTime())) return isoString;
        return kstFormatter.format(d);
    }

    /**
     * WebSocket stomp 연결. market-data가 /topic/quotes/{code} 로 발행할 때까지는 비활성 허브.
     * TODO: market-data 모듈이 core 이벤트(RealtimeQuoteEvent 등)를 추가·머지한 이후 활성화.
     */
    function connectQuoteStream(opts) {
        const el = document.querySelector('[data-ws-status]');
        if (el) {
            el.dataset.state = 'disconnected';
            el.textContent = '실시간 시세 대기중 (market-data 연결 예정)';
        }
        if (opts && typeof opts.onStub === 'function') opts.onStub();
    }

    global.MagicJar = {
        formatKrw,
        formatPct,
        formatKst,
        connectQuoteStream,
    };
})(window);
