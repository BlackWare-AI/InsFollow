(function () {
    'use strict';
    const expected = __EXPECTED__;
    const url = new URL(location.href);
    if (!['www.instagram.com', 'instagram.com'].includes(url.hostname) || url.protocol !== 'https:') return 'UNKNOWN';
    const text = (document.body?.innerText || '').slice(0, 80000).toLocaleLowerCase('en-US');
    const restricted = ['challenge', 'checkpoint', '/accounts/suspended'].some(x => url.pathname.includes(x)) ||
        ['try again later', 'we restrict certain activity', 'confirm it’s you', 'confirm it\'s you', 'şüpheli giriş', 'daha sonra tekrar dene', 'işlem engellendi', 'hesabını onayla'].some(x => text.includes(x));
    if (restricted) return 'RESTRICTED';
    if (url.pathname.startsWith('/accounts/login') || document.querySelector('input[type="password"]')) return 'LOGIN';
    if (url.pathname.toLowerCase().replace(/^\/+|\/+$/g, '') !== expected) return 'UNKNOWN';
    const main = document.querySelector('main');
    if (!main) return 'UNKNOWN';
    const unavailable = ['sorry, this page isn\'t available', 'the link you followed may be broken', 'üzgünüz, bu sayfaya ulaşılamıyor', 'bu sayfa kullanılamıyor'].some(x => text.includes(x));
    if (unavailable) return 'UNAVAILABLE';
    const header = main.querySelector('header');
    if (!header) return 'UNKNOWN';
    const headings = Array.from(header.querySelectorAll('h1,h2,h3,[role="heading"]')).map(x => x.textContent.trim().toLowerCase());
    const og = document.querySelector('meta[property="og:title"]')?.content?.toLowerCase() || '';
    if (!headings.includes(expected) && !og.includes('(@' + expected + ')')) return 'UNKNOWN';
    const visible = x => !!(x.getClientRects().length && getComputedStyle(x).visibility !== 'hidden');
    const labels = Array.from(header.querySelectorAll('button,[role="button"],a')).filter(visible).map(x => x.innerText.trim().toLocaleLowerCase('tr-TR'));
    const states = new Set();
    labels.forEach(x => {
        if (['following', 'takiptesin', 'takip ediliyor'].includes(x)) states.add('FOLLOWING');
        if (['requested', 'istek gönderildi', 'talep edildi'].includes(x)) states.add('REQUESTED');
        if (['follow', 'follow back', 'takip et', 'sen de takip et'].includes(x)) states.add('FOLLOW');
    });
    return states.size === 1 ? Array.from(states)[0] : 'UNKNOWN';
})();
