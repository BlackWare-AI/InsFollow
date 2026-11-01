(function () {
    'use strict';
    const expected = __EXPECTED__;
    const url = new URL(location.href);
    if (url.protocol !== 'https:' || !['www.instagram.com', 'instagram.com'].includes(url.hostname) ||
        url.pathname.toLowerCase().replace(/^\/+|\/+$/g, '') !== expected) return 'WRONG_PROFILE';

    const main = document.querySelector('main');
    const header = main?.querySelector('header');
    if (!header) return 'NO_HEADER';

    // Require the same identity evidence as the read-only inspection.
    const headings = Array.from(header.querySelectorAll('h1,h2,h3,[role="heading"]'))
        .map(x => (x.textContent || '').trim().toLowerCase());
    const og = (document.querySelector('meta[property="og:title"]')?.content || '').toLowerCase();
    if (!headings.includes(expected) && !og.includes('(@' + expected + ')')) return 'WRONG_PROFILE';

    const visible = x => {
        const style = getComputedStyle(x);
        const rect = x.getBoundingClientRect();
        return x.getClientRects().length > 0 && rect.width > 0 && rect.height > 0 &&
            style.visibility !== 'hidden' && style.display !== 'none';
    };
    const labels = new Set(['follow', 'follow back', 'takip et', 'sen de takip et']);
    const matches = Array.from(header.querySelectorAll('button,[role="button"]'))
        .filter(x => visible(x) && !x.disabled && x.getAttribute('aria-disabled') !== 'true')
        .filter(x => labels.has((x.innerText || x.textContent || '').trim().toLocaleLowerCase('tr-TR')));
    if (matches.length !== 1) return 'NOT_FOUND';

    try {
        matches[0].scrollIntoView({ block: 'center', behavior: 'auto' });
        matches[0].click();
        return 'CLICKED'; // A dispatched DOM event, never confirmation of a Follow.
    } catch (_) {
        return 'FAILED';
    }
})();
