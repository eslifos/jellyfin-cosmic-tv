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
