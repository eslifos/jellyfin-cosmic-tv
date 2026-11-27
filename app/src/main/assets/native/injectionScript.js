(() => {
    // On a television the page is laid out for a 1920 px wide screen, the size the TV layout is made for, and fitted
    // to the screen whatever density the TV reports (many say 2x, which would lay it out at 960 px)
    if (window.NativeInterface && window.NativeInterface.isTelevision()) {
        let viewport = document.querySelector('meta[name="viewport"]');
        if (!viewport) {
            viewport = document.createElement('meta');
            viewport.name = 'viewport';
            document.head.appendChild(viewport);
        }
        viewport.content = 'width=1920, user-scalable=no';
    }

    // The TV app keeps its own loading screen until the web shows its first screen: the first one that stays (on
    // start the web may go on at once to another one, as Who is watching?)
    if (window.NativeInterface && typeof window.NativeInterface.webReady === 'function') {
        const SETTLE_MS = 800;
        let timer = 0;
        const onViewShow = () => {
            clearTimeout(timer);
            timer = setTimeout(() => {
                document.removeEventListener('viewshow', onViewShow, true);
                window.NativeInterface.webReady();
            }, SETTLE_MS);
        };
        document.addEventListener('viewshow', onViewShow, true);
    }

    const scripts = [
        '/native/nativeshell.js',
        '/native/EventEmitter.js',
        document.currentScript.src.concat('?deferred=true&ts=', Date.now())
    ];
    for (const script of scripts) {
        const scriptElement = document.createElement('script');
        scriptElement.src = script;
        scriptElement.charset = 'utf-8';
        scriptElement.setAttribute('defer', '');
        document.body.appendChild(scriptElement);
    }
    document.currentScript.remove();
})();
