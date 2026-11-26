export class NavigationPlugin {
    constructor({ playbackManager }) {
        window['NavigationHelper'] = this;

        this.playbackManager = playbackManager;
    }

    goBack() {
        var appRouter = window['Emby']['Page'];
        // Cosmic decides: back, or (TV home screen) to its navigation bar first; the app closes only when it says so
        if (typeof appRouter.handleBack === 'function') {
            if (!appRouter.handleBack()) {
                window['NativeInterface'].exitApp();
            }
            return;
        }
        if (appRouter.canGoBack()) {
            appRouter.back();
        } else {
            window['NativeInterface'].exitApp();
        }
    }
}
