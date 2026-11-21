(() => {
    'use strict';

    const token = new URLSearchParams(location.search).get('t') || '';
    const statusEl = document.getElementById('status');
    const app = document.getElementById('app');
    const bye = document.getElementById('bye');
    const stopBtn = document.getElementById('stopBtn');
    const urlInput = document.getElementById('url');
    const textInput = document.getElementById('text');

    const buzz = (ms = 8) => {
        if (navigator.vibrate) navigator.vibrate(ms);
    };

    const setStatus = (ok) => {
        statusEl.classList.toggle('on', ok);
        statusEl.title = ok ? 'Connected' : 'No connection';
    };

    async function send(type, data = {}) {
        try {
            const response = await fetch('/api/command?t=' + encodeURIComponent(token), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(Object.assign({ type }, data)),
                cache: 'no-store'
            });
            setStatus(response.ok);
            return response.ok;
        } catch (_) {
            setStatus(false);
            return false;
        }
    }

    // The page itself came over the connection, so the TV is reachable.
    setStatus(true);

    // A direction button sends the key again while it is held.
    let directionTimer = 0;
    let directionKey = null;

    function startDirection(key) {
        if (directionKey === key && directionTimer) return;
        stopDirection();
        directionKey = key;
        send('key', { key });
        directionTimer = setInterval(() => send('key', { key }), 140);
    }

    function stopDirection() {
        if (directionTimer) clearInterval(directionTimer);
        directionTimer = 0;
        directionKey = null;
    }

    // a release anywhere stops a held direction, also when the finger leaves the button
    window.addEventListener('pointerup', stopDirection);
    window.addEventListener('pointercancel', stopDirection);
    window.addEventListener('blur', stopDirection);
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) stopDirection();
    });

    document.getElementById('openForm').addEventListener('submit', (event) => {
        event.preventDefault();
        const value = urlInput.value.trim();
        if (!value) return;
        send('url', { url: value }).then((ok) => {
            if (ok) {
                urlInput.blur();
                buzz(12);
            }
        });
    });

    document.querySelectorAll('[data-key]').forEach((button) => {
        const key = button.dataset.key;
        if (key === 'up' || key === 'down' || key === 'left' || key === 'right') {
            // a direction moves the cursor while the button is held
            button.addEventListener('pointerdown', (event) => {
                event.preventDefault();
                try {
                    button.setPointerCapture(event.pointerId);
                } catch (_) {
                    // the page still gets the release
                }
                buzz();
                startDirection(key);
            });
            const stop = (event) => {
                event.preventDefault();
                stopDirection();
            };
            button.addEventListener('pointerup', stop);
            button.addEventListener('pointercancel', stop);
            button.addEventListener('lostpointercapture', stopDirection);
        } else {
            button.addEventListener('click', () => {
                buzz();
                send('key', { key });
            });
        }
    });

    document.querySelectorAll('[data-seek]').forEach((button) => {
        button.addEventListener('click', () => {
            buzz();
            send('key', { key: button.dataset.seek });
        });
    });

    document.getElementById('scrollUp').addEventListener('click', () => {
        buzz();
        send('scroll', { dy: -650 });
    });
    document.getElementById('scrollDown').addEventListener('click', () => {
        buzz();
        send('scroll', { dy: 650 });
    });

    document.getElementById('sendText').addEventListener('click', () => {
        const value = textInput.value;
        if (!value) return;
        buzz();
        send('text', { text: value }).then((ok) => {
            //close the phone keyboard: the D-pad buttons are then one tap away
            if (ok) textInput.blur();
        });
    });

    stopBtn.addEventListener('click', async () => {
        stopBtn.disabled = true;
        await send('stop');
        app.hidden = true;
        bye.hidden = false;
        document.title = 'TV Bro — closed';
        buzz(20);
    });
})();
