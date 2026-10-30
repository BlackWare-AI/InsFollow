(function () {
    'use strict';
    const expected = __EXPECTED__;
    const url = new URL(location.href);
    const out = (privacy, count, reason) => JSON.stringify({privacy, count, reason});
    if (url.protocol !== 'https:' || !['instagram.com','www.instagram.com'].includes(url.hostname)) return out('UNKNOWN','','UNKNOWN_PAGE_STATE');
    const body = (document.body?.innerText || '').slice(0,80000).toLocaleLowerCase('tr-TR');
    if (['challenge','checkpoint','suspended'].some(x => url.pathname.includes(x)) || ['try again later','we restrict certain activity',"confirm it's you",'confirm it’s you','daha sonra tekrar dene','işlem engellendi','hesabını onayla','şüpheli giriş'].some(x => body.includes(x))) return out('UNKNOWN','','ACTION_RESTRICTED');
    if (url.pathname.startsWith('/accounts/login') || document.querySelector('input[type="password"]')) return out('UNKNOWN','','LOGIN_REQUIRED');
    if (url.pathname.replace(/^\/+|\/+$/g,'').toLowerCase() !== expected) return out('UNKNOWN','','UNKNOWN_PAGE_STATE');
    const main = document.querySelector('main');
    if (!main) return out('UNKNOWN','','');
    if (["sorry, this page isn't available",'the link you followed may be broken','üzgünüz, bu sayfaya ulaşılamıyor','bu sayfa kullanılamıyor'].some(x => body.includes(x))) return out('UNKNOWN','','PROFILE_UNAVAILABLE');
    const header = main.querySelector('header');
    const headings = Array.from(header?.querySelectorAll('h1,h2,h3,[role="heading"]') || []).map(x => x.textContent.trim().toLowerCase());
    const og = document.querySelector('meta[property="og:title"]')?.content?.toLowerCase() || '';
    if (!header || (!headings.includes(expected) && !og.includes('(@'+expected+')'))) return out('UNKNOWN','','');
    const visible = x => !!(x.getClientRects().length && getComputedStyle(x).visibility !== 'hidden');
    const privateEvidence = ['this account is private','bu hesap gizli','bu hesap gizlidir'].some(x => body.includes(x));
    // Seeing posts while following does not establish that an account is public.
    const follow = Array.from(header.querySelectorAll('button,[role="button"]')).filter(visible).some(x => ['follow','follow back','takip et','sen de takip et'].includes(x.innerText.trim().toLocaleLowerCase('tr-TR')));
    const publicEvidence = follow && Array.from(main.querySelectorAll('article a[href*="/p/"],a[href*="/reel/"]')).some(visible);
    const privacy = privateEvidence ? 'PRIVATE' : publicEvidence ? 'PUBLIC' : 'UNKNOWN';
    const candidates = [];
    Array.from(header.querySelectorAll('a,li')).filter(visible).forEach(node => {
        const label = node.innerText.trim();
        if (!/(?:followers|takipçi)$/i.test(label)) return;
        const titled = node.querySelector('[title]')?.getAttribute('title');
        const number = titled || label.replace(/\s*(followers|takipçi)\s*$/i,'').trim();
        if (/^[0-9][0-9.,\s]*[KMB]?$/i.test(number)) candidates.push(number);
    });
    const unique = [...new Set(candidates)];
    return out(privacy,unique.length===1 ? unique[0] : '','');
})();
