(function () {
    'use strict';
    const expected = __EXPECTED__;
    const followLabels = new Set(['follow', 'follow back', 'takip et', 'sen de takip et']);
    const followingLabels = new Set(['following', 'takiptesin', 'takip ediliyor']);
    const requestedLabels = new Set(['requested', 'istek gönderildi', 'talep edildi']);
    const visible = x => {
        const style = getComputedStyle(x);
        const rect = x.getBoundingClientRect();
        return x.getClientRects().length > 0 && rect.width > 0 && rect.height > 0 &&
            style.visibility !== 'hidden' && style.display !== 'none';
    };
    const guard = () => {
        const url = new URL(location.href);
        if (url.protocol !== 'https:' || !['www.instagram.com', 'instagram.com'].includes(url.hostname)) return { status: 'WRONG_PROFILE' };
        const body = document.body?.innerText || '';
        // Never ignore a warning because it falls outside a truncated text window.
        if (body.length > 80000) return { status: 'AMBIGUOUS' };
        const forms = [body.toLocaleLowerCase('en-US'), body.toLocaleLowerCase('tr-TR')];
        const restricted = ['challenge', 'checkpoint', 'suspended'].some(x => url.pathname.includes(x)) ||
            ['try again later', 'we restrict certain activity', 'confirm it’s you', "confirm it's you", 'şüpheli giriş', 'daha sonra tekrar dene', 'işlem engellendi', 'hesabını onayla']
                .some(x => forms.some(text => text.includes(x)));
        if (restricted) return { status: 'RESTRICTED' };
        if (url.pathname.startsWith('/accounts/login') || document.querySelector('input[type="password"]')) return { status: 'LOGIN' };
        if (url.pathname.toLowerCase().replace(/^\/+|\/+$/g, '') !== expected) return { status: 'WRONG_PROFILE' };
        const header = document.querySelector('main')?.querySelector('header');
        if (!header) return { status: 'NO_HEADER' };
        const headings = Array.from(header.querySelectorAll('h1,h2,h3,[role="heading"]'))
            .map(x => (x.textContent || '').trim().toLowerCase());
        const og = (document.querySelector('meta[property="og:title"]')?.content || '').toLowerCase();
        if (!headings.includes(expected) && !og.includes('(@' + expected + ')')) return { status: 'WRONG_PROFILE' };
        const controls = Array.from(header.querySelectorAll('button,[role="button"],a')).filter(visible);
        const label = x => (x.innerText || x.textContent || '').trim().toLocaleLowerCase('tr-TR');
        const states = new Set();
        controls.forEach(x => {
            const text = label(x);
            if (followLabels.has(text)) states.add('FOLLOW');
            if (followingLabels.has(text)) states.add('FOLLOWING');
            if (requestedLabels.has(text)) states.add('REQUESTED');
        });
        if (states.size > 1) return { status: 'AMBIGUOUS' };
        if (states.has('FOLLOWING') || states.has('REQUESTED')) return { status: 'STATE_CHANGED' };
        const matches = controls.filter(x => x.matches('button,[role="button"]') &&
            !x.disabled && !x.matches(':disabled') && x.getAttribute('aria-disabled') !== 'true' &&
            !x.closest('[inert]') && followLabels.has(label(x)));
        if (matches.length !== 1) return { status: 'NOT_FOUND' };
        return { status: 'READY', node: matches[0] };
    };
    const first = guard();
    if (first.status !== 'READY') return first.status;
    try {
        first.node.scrollIntoView({ block: 'center', behavior: 'auto' });
        const final = guard();
        if (final.status !== 'READY') return final.status;
        if (final.node !== first.node) return 'AMBIGUOUS';
        final.node.click();
        const after = guard();
        if (after.status === 'LOGIN' || after.status === 'RESTRICTED') return after.status;
        return 'CLICKED'; // Dispatch only; independent refresh verification is still required.
    } catch (_) {
        // The exception could have occurred after dispatch. Never claim a definite non-click.
        return 'UNCERTAIN';
    }
})();
