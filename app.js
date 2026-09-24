/* Universal MP3 Player
 * Runs both as a plain web page and inside the Android WebView shell.
 * When the Android shell is present, window.UniversalNative provides
 * MediaStore scanning, native MediaPlayer playback and MP3 downloads.
 */
(function () {
  'use strict';

  var $ = function (sel, root) { return (root || document).querySelector(sel); };
  var $$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };
  var native = window.UniversalNative || null;

  var STORAGE = 'universal-mp3-v4';
  var DOWNLOAD_STORAGE = 'universal-mp3-downloads';

  var audio = $('#audio');
  var fileInput = $('#fileInput');

  var db = null;
  var tracks = [];
  var currentId = null;
  var queue = [];
  var shuffle = false;
  var repeat = false;
  var favorites = new Set();
  var playlists = {};
  var history = [];
  var discoverItems = [];
  var discoverType = 'all';
  var libraryTab = 'songs';
  var sleepTimeout = null;
  var nativePlaying = false;

  function fmt(seconds) {
    if (!isFinite(seconds) || seconds < 0) return '0:00';
    var m = Math.floor(seconds / 60);
    var s = Math.floor(seconds % 60);
    return m + ':' + String(s).padStart(2, '0');
  }

  function esc(value) {
    return String(value === null || value === undefined ? '' : value)
      .replace(/[&<>"']/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
      });
  }

  function uid() {
    return window.crypto && crypto.randomUUID ? crypto.randomUUID() : 'local-' + Date.now() + '-' + Math.random().toString(16).slice(2);
  }

  function toast(message) {
    var el = $('#toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'toast';
      el.className = 'toast';
      document.body.appendChild(el);
    }
    el.textContent = message;
    el.classList.add('show');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(function () { el.classList.remove('show'); }, 3200);
  }

  /* ---------------------------------------------------------------- storage */

  function persist() {
    try {
      localStorage.setItem(STORAGE, JSON.stringify({
        favorites: Array.from(favorites), playlists: playlists, history: history, shuffle: shuffle, repeat: repeat
      }));
    } catch (e) { /* storage full or disabled */ }
  }

  function restore() {
    try {
      var saved = JSON.parse(localStorage.getItem(STORAGE) || '{}');
      favorites = new Set(saved.favorites || []);
      playlists = saved.playlists || {};
      history = saved.history || [];
      shuffle = !!saved.shuffle;
      repeat = !!saved.repeat;
    } catch (e) { /* ignore corrupt state */ }
  }

  function initDB() {
    return new Promise(function (resolve) {
      if (!window.indexedDB) return resolve();
      var request = indexedDB.open('UniversalMp3Library', 1);
      request.onupgradeneeded = function () {
        if (!request.result.objectStoreNames.contains('tracks')) {
          request.result.createObjectStore('tracks', { keyPath: 'id' });
        }
      };
      request.onsuccess = function () { db = request.result; resolve(); };
      request.onerror = function () { resolve(); };
    });
  }

  function store(mode) {
    return db.transaction('tracks', mode || 'readonly').objectStore('tracks');
  }

  function putTrack(track) {
    return new Promise(function (resolve) {
      if (!db) return resolve();
      var copy = Object.assign({}, track);
      delete copy.url;
      var request = store('readwrite').put(copy);
      request.onsuccess = function () { resolve(); };
      request.onerror = function () { resolve(); };
    });
  }

  function getStoredTracks() {
    return new Promise(function (resolve) {
      if (!db) return resolve([]);
      var request = store().getAll();
      request.onsuccess = function () { resolve(request.result || []); };
      request.onerror = function () { resolve([]); };
    });
  }

  /* ---------------------------------------------------------------- library */

  function sortTracks() {
    tracks.sort(function (a, b) {
      return String(a.title || '').localeCompare(String(b.title || ''), undefined, { sensitivity: 'base' });
    });
  }

  function scanPhoneMusic() {
    if (!native || typeof native.scanMusic !== 'function') {
      setLibraryStatus('Phone scanning is available in the Android app.');
      return 0;
    }
    var found = [];
    try {
      found = JSON.parse(native.scanMusic() || '[]');
    } catch (e) {
      setLibraryStatus('Could not read the music on this phone.');
      return 0;
    }
    var known = new Set(tracks.map(function (t) { return t.id; }));
    found.forEach(function (t) {
      if (!known.has(t.id)) tracks.push(t);
    });
    sortTracks();
    if (found.length) {
      setLibraryStatus(found.length + ' song' + (found.length === 1 ? '' : 's') + ' found on this phone');
    } else if (native.hasAudioPermission && !native.hasAudioPermission()) {
      setLibraryStatus('Allow the music permission so the app can list the songs on your phone.');
    } else {
      setLibraryStatus('No music files were found on this phone.');
    }
    renderAll();
    return found.length;
  }

  function setLibraryStatus(message) {
    var el = $('#recentStatus');
    if (el) el.textContent = message;
  }

  function addFiles(files) {
    var accepted = files.filter(function (file) {
      return (file.type && file.type.indexOf('audio/') === 0) || /\.(mp3|m4a|flac|wav|ogg|opus|aac)$/i.test(file.name);
    });
    var chain = Promise.resolve();
    accepted.forEach(function (file) {
      chain = chain.then(function () {
        var track = {
          id: uid(),
          name: file.name,
          title: file.name.replace(/\.[^.]+$/, '') || 'Untitled',
          artist: 'Unknown artist',
          album: 'Unknown album',
          genre: 'Unknown',
          year: '',
          folder: 'Imported',
          duration: 0,
          blob: file,
          addedAt: Date.now(),
          lastPlayed: 0,
          cover: ''
        };
        return putTrack(track).then(function () {
          track.url = URL.createObjectURL(file);
          tracks.push(track);
        });
      });
    });
    return chain.then(function () {
      sortTracks();
      renderAll();
      if (accepted.length) toast(accepted.length + ' song' + (accepted.length === 1 ? '' : 's') + ' added');
    });
  }

  function loadLibrary() {
    return getStoredTracks().then(function (stored) {
      stored.forEach(function (track) {
        if (!track.blob) return;
        track.url = URL.createObjectURL(track.blob);
        tracks.push(track);
      });
      scanPhoneMusic();
      sortTracks();
      renderAll();
    });
  }

  function findTrack(trackId) {
    return tracks.find(function (t) { return t.id === trackId; }) || null;
  }

  function currentTrack() {
    return currentId ? findTrack(currentId) : null;
  }

  function isNativeTrack(track) {
    return !!(track && track.native && native && typeof native.playMusic === 'function');
  }

  /* -------------------------------------------------------------- playback */

  function setPlayIcon(playing) {
    $('#playBtn').textContent = playing ? 'Ⅱ' : '▶';
  }

  function playTrack(trackId, rebuildQueue) {
    var track = findTrack(trackId);
    if (!track) return;
    if (rebuildQueue !== false) {
      queue = tracks.map(function (t) { return t.id; });
    }
    currentId = trackId;

    $('#nowTitle').textContent = track.title;
    $('#nowArtist').textContent = track.artist + (track.album && track.album !== 'Unknown album' ? ' · ' + track.album : '');
    $('#miniCover').innerHTML = track.cover ? '<img src="' + esc(track.cover) + '" alt="">' : '♫';
    $('#progress').value = 0;
    $('#currentTime').textContent = '0:00';
    $('#duration').textContent = fmt(track.duration || 0);

    if (isNativeTrack(track)) {
      try { audio.pause(); } catch (e) { /* ignore */ }
      audio.removeAttribute('src');
      nativePlaying = true;
      $('#playBtn').textContent = '…';
      native.playMusic(Number(track.mediaId));
    } else {
      if (native && typeof native.stopMusic === 'function') native.stopMusic();
      nativePlaying = false;
      audio.src = track.url || '';
      audio.load();
      audio.play().then(function () { setPlayIcon(true); }).catch(function () {
        setPlayIcon(false);
        toast('This file could not be played.');
      });
    }

    track.lastPlayed = Date.now();
    history = [trackId].concat(history.filter(function (x) { return x !== trackId; })).slice(0, 100);
    persist();
    updateLike();
    renderRecent();
    renderLibrary();
    renderQueue();
  }

  function playbackOrder() {
    var ids = queue.length ? queue.slice() : tracks.map(function (t) { return t.id; });
    return ids.filter(function (id) { return findTrack(id); });
  }

  function next(auto) {
    if (!tracks.length) return;
    if (auto && repeat && currentId) { playTrack(currentId, false); return; }
    var ids = playbackOrder();
    if (!ids.length) return;
    if (shuffle && ids.length > 1) {
      var options = ids.filter(function (id) { return id !== currentId; });
      playTrack(options[Math.floor(Math.random() * options.length)], false);
      return;
    }
    var index = ids.indexOf(currentId);
    playTrack(ids[(index + 1 + ids.length) % ids.length], false);
  }

  function prev() {
    if (!currentId) return;
    var track = currentTrack();
    var position = isNativeTrack(track) ? native.nativePosition() / 1000 : audio.currentTime;
    if (position > 5) {
      if (isNativeTrack(track)) native.seekMusic(0); else audio.currentTime = 0;
      return;
    }
    var ids = playbackOrder();
    var index = ids.indexOf(currentId);
    playTrack(ids[(index - 1 + ids.length) % ids.length], false);
  }

  function togglePlay() {
    var track = currentTrack();
    if (!track) {
      if (tracks.length) playTrack(tracks[0].id);
      else toast('Add or scan music first.');
      return;
    }
    if (isNativeTrack(track)) {
      if (native.isNativePlaying()) { native.pauseMusic(); setPlayIcon(false); }
      else { native.resumeMusic(); setPlayIcon(true); }
      return;
    }
    if (audio.paused) audio.play().then(function () { setPlayIcon(true); }).catch(function () { setPlayIcon(false); });
    else { audio.pause(); setPlayIcon(false); }
  }

  function seekTo(percent) {
    var track = currentTrack();
    if (isNativeTrack(track)) {
      var duration = native.nativeDuration();
      if (duration > 0) native.seekMusic(Math.round(percent / 100 * duration));
    } else if (audio.duration) {
      audio.currentTime = percent / 100 * audio.duration;
    }
  }

  window.nativePlaybackReady = function () {
    nativePlaying = true;
    setPlayIcon(true);
    var duration = native && native.nativeDuration ? native.nativeDuration() : 0;
    if (duration) $('#duration').textContent = fmt(duration / 1000);
  };

  window.nativePlaybackError = function (message) {
    nativePlaying = false;
    setPlayIcon(false);
    toast(message || 'Playback failed.');
  };

  window.nativePlaybackEnded = function () {
    nativePlaying = false;
    setPlayIcon(false);
    next(true);
  };

  window.nativePlaybackPaused = function () {
    setPlayIcon(false);
  };

  window.nativePermissionReady = function () {
    scanPhoneMusic();
  };

  window.nativeDownloadComplete = function (title, url) {
    saveDownload({ title: title, artist: 'Internet Archive', source: 'Internet Archive', url: url });
    toast('Downloaded: ' + title);
    scanPhoneMusic();
  };

  window.nativeDownloadFailed = function (message) {
    toast(message || 'Download failed.');
  };

  setInterval(function () {
    var track = currentTrack();
    if (!isNativeTrack(track) || !nativePlaying) return;
    var position = native.nativePosition();
    var duration = native.nativeDuration();
    $('#currentTime').textContent = fmt(position / 1000);
    if (duration > 0) {
      $('#duration').textContent = fmt(duration / 1000);
      $('#progress').value = (position / duration) * 100;
    }
    setPlayIcon(native.isNativePlaying());
  }, 500);

  /* -------------------------------------------------------------- rendering */

  function trackCard(track) {
    return '<article class="track-card" data-play="' + esc(track.id) + '">' +
      '<div class="cover">' + (track.cover ? '<img src="' + esc(track.cover) + '" alt="">' : '♫') + '</div>' +
      '<strong>' + esc(track.title) + '</strong><span>' + esc(track.artist) + '</span></article>';
  }

  function trackRow(track) {
    var liked = favorites.has(track.id);
    return '<div class="song-row" data-play="' + esc(track.id) + '">' +
      '<div class="row-cover">' + (track.cover ? '<img src="' + esc(track.cover) + '" alt="">' : '♫') + '</div>' +
      '<div class="row-meta"><strong>' + esc(track.title) + '</strong><span>' + esc(track.artist) + ' · ' + esc(track.album) + '</span></div>' +
      '<span>' + fmt(track.duration) + '</span>' +
      '<button class="icon-btn' + (liked ? ' on' : '') + '" data-like="' + esc(track.id) + '">' + (liked ? '♥' : '♡') + '</button>' +
      '<button class="small-btn" data-more="' + esc(track.id) + '">⋯</button></div>';
  }

  function groupRow(label, list, icon) {
    return '<div class="song-row" data-play="' + esc(list[0].id) + '"><div class="row-cover">' + icon + '</div>' +
      '<div class="row-meta"><strong>' + esc(label) + '</strong><span>' + list.length + ' song' + (list.length === 1 ? '' : 's') + '</span></div>' +
      '<span></span><span></span><button class="small-btn">Play</button></div>';
  }

  function groupBy(list, key) {
    var map = new Map();
    list.forEach(function (track) {
      var value = track[key] || 'Unknown';
      if (!map.has(value)) map.set(value, []);
      map.get(value).push(track);
    });
    return map;
  }

  function renderRecent() {
    var recent = history.map(findTrack).filter(Boolean).slice(0, 10);
    $('#recentGrid').innerHTML = recent.map(trackCard).join('');
    $('#emptyState').style.display = tracks.length ? 'none' : 'block';
  }

  function renderLibrary() {
    var el = $('#libraryList');
    if (!el) return;
    var query = $('#searchInput').value.trim().toLowerCase();
    var list = tracks.filter(function (t) {
      return !query || (t.title + ' ' + t.artist + ' ' + t.album + ' ' + t.genre).toLowerCase().indexOf(query) !== -1;
    });

    if (libraryTab === 'recent') {
      list = history.map(findTrack).filter(Boolean);
      el.innerHTML = list.map(trackRow).join('') || '<div class="empty-state">Nothing played yet.</div>';
    } else if (libraryTab === 'songs') {
      el.innerHTML = list.map(trackRow).join('') || '<div class="empty-state">No songs found.</div>';
    } else if (libraryTab === 'albums') {
      el.innerHTML = Array.from(groupBy(list, 'album')).map(function (entry) {
        return groupRow(entry[0], entry[1], '♫');
      }).join('') || '<div class="empty-state">No albums found.</div>';
    } else if (libraryTab === 'artists') {
      el.innerHTML = Array.from(groupBy(list, 'artist')).map(function (entry) {
        return groupRow(entry[0], entry[1], '♬');
      }).join('') || '<div class="empty-state">No artists found.</div>';
    } else {
      el.innerHTML = Array.from(groupBy(list, 'folder')).map(function (entry) {
        return groupRow(entry[0], entry[1], '📁');
      }).join('') || '<div class="empty-state">No folders found.</div>';
    }
  }

  function renderFavorites() {
    var list = tracks.filter(function (t) { return favorites.has(t.id); });
    $('#favoritesList').innerHTML = list.map(trackRow).join('') || '<div class="empty-state">No liked songs yet.</div>';
  }

  function renderPlaylists() {
    $('#playlistList').innerHTML = Object.keys(playlists).map(function (name) {
      var ids = playlists[name];
      return '<article class="playlist-card" data-playlist="' + esc(name) + '"><div class="playlist-cover">♫</div>' +
        '<strong>' + esc(name) + '</strong><span>' + ids.length + ' song' + (ids.length === 1 ? '' : 's') + '</span></article>';
    }).join('') || '<div class="empty-state">Create your first playlist.</div>';
  }

  function savedDownloads() {
    try { return JSON.parse(localStorage.getItem(DOWNLOAD_STORAGE) || '[]'); } catch (e) { return []; }
  }

  function saveDownload(item) {
    var all = savedDownloads();
    all.unshift(Object.assign({ at: Date.now() }, item));
    try { localStorage.setItem(DOWNLOAD_STORAGE, JSON.stringify(all.slice(0, 80))); } catch (e) { /* ignore */ }
    renderDownloads();
  }

  function renderDownloads() {
    var saved = savedDownloads();
    $('#downloadsList').innerHTML = saved.map(function (d) {
      return '<div class="song-row"><div class="row-cover">⇩</div>' +
        '<div class="row-meta"><strong>' + esc(d.title) + '</strong><span>' + esc(d.artist) + ' · ' + esc(d.source) + '</span></div>' +
        '<span></span><span></span></div>';
    }).join('');
    $('#downloadInfo').style.display = saved.length ? 'none' : 'block';
  }

  function renderStats() {
    $('#songStat').textContent = tracks.length;
    $('#albumStat').textContent = new Set(tracks.map(function (t) { return t.album; })).size;
    $('#artistStat').textContent = new Set(tracks.map(function (t) { return t.artist; })).size;
    $('#playlistStat').textContent = Object.keys(playlists).length;
  }

  function renderQueue() {
    var items = playbackOrder().map(function (id, index) {
      var track = findTrack(id);
      return '<div class="queue-item' + (id === currentId ? ' on' : '') + '" data-play="' + esc(id) + '"><span>' + (index + 1) + '.</span>' +
        '<div class="row-meta"><strong>' + esc(track.title) + '</strong><span>' + esc(track.artist) + '</span></div></div>';
    });
    $('#queueList').innerHTML = items.join('') || '<p class="muted">Queue is empty.</p>';
  }

  function updateLike() {
    var liked = !!currentId && favorites.has(currentId);
    $('#likeBtn').textContent = liked ? '♥' : '♡';
    $('#likeBtn').classList.toggle('on', liked);
  }

  function renderAll() {
    renderStats();
    renderRecent();
    renderLibrary();
    renderFavorites();
    renderPlaylists();
    renderDownloads();
    renderQueue();
    updateLike();
  }

  function showView(view) {
    $$('[data-view]').forEach(function (b) { b.classList.toggle('active', b.dataset.view === view); });
    $$('.view').forEach(function (v) { v.classList.remove('active-view'); });
    var target = $('#' + view + 'View');
    if (target) target.classList.add('active-view');
    if (view === 'library') renderLibrary();
    if (view === 'favorites') renderFavorites();
    if (view === 'playlists') renderPlaylists();
    if (view === 'downloads') renderDownloads();
    window.scrollTo(0, 0);
  }

  /* --------------------------------------------------------------- discover */

  function fetchJSON(url) {
    return fetch(url).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    });
  }

  function searchCatalog(query) {
    if (native && typeof native.searchCatalog === 'function') {
      return Promise.resolve().then(function () { return JSON.parse(native.searchCatalog(query) || '[]'); });
    }
    return fetchJSON('https://itunes.apple.com/search?term=' + encodeURIComponent(query) + '&entity=song&limit=30')
      .then(function (data) { return data.results || []; });
  }

  function searchFreeDownloads(query) {
    if (native && typeof native.searchFreeMusic === 'function') {
      return Promise.resolve().then(function () { return JSON.parse(native.searchFreeMusic(query) || '[]'); });
    }
    var url = 'https://archive.org/advancedsearch.php?q=' + encodeURIComponent('mediatype:audio AND ' + query) +
      '&fl[]=identifier&fl[]=title&fl[]=creator&rows=10&output=json';
    return fetchJSON(url).then(function (data) {
      var docs = (data.response && data.response.docs) || [];
      return Promise.all(docs.slice(0, 6).map(function (doc) {
        return fetchJSON('https://archive.org/metadata/' + encodeURIComponent(doc.identifier)).then(function (meta) {
          var file = (meta.files || []).find(function (f) {
            return String(f.name || '').toLowerCase().endsWith('.mp3') && Number(f.size || 0) > 0;
          });
          if (!file) return null;
          return {
            identifier: doc.identifier,
            title: doc.title || doc.identifier,
            artist: doc.creator || 'Unknown artist',
            source: 'Internet Archive',
            downloadUrl: 'https://archive.org/download/' + encodeURIComponent(doc.identifier) + '/' +
              String(file.name).split('/').map(encodeURIComponent).join('/')
          };
        }).catch(function () { return null; });
      })).then(function (items) { return items.filter(Boolean); });
    });
  }

  var freeItems = [];

  function discover(query) {
    query = String(query || '').trim();
    if (!query) return;
    showView('discover');
    $('#discoverStatus').textContent = 'Searching…';
    $('#freeDownloadStatus').textContent = 'Searching the open music source…';

    searchCatalog(query).then(function (results) {
      discoverItems = results;
      renderDiscover();
    }).catch(function () {
      discoverItems = [];
      $('#discoverGrid').innerHTML = '';
      $('#discoverStatus').textContent = 'Could not reach the music catalogue. Check your connection.';
    });

    searchFreeDownloads(query).then(function (items) {
      freeItems = items;
      renderFree();
      $('#freeDownloadStatus').textContent = items.length
        ? items.length + ' downloadable result' + (items.length === 1 ? '' : 's') + ' from the Internet Archive'
        : 'No downloadable result for this search.';
    }).catch(function () {
      freeItems = [];
      renderFree();
      $('#freeDownloadStatus').textContent = 'Could not reach the Internet Archive.';
    });
  }

  function renderDiscover() {
    var items = discoverItems.filter(function (x) {
      if (discoverType === 'all') return true;
      if (discoverType === 'song') return x.wrapperType === 'track';
      if (discoverType === 'album') return x.collectionType === 'Album';
      return x.wrapperType === 'artist';
    });
    $('#discoverStatus').textContent = items.length ? items.length + ' result' + (items.length === 1 ? '' : 's') : 'No results found.';
    $('#discoverGrid').innerHTML = items.map(function (x, i) {
      var title = x.trackName || x.collectionName || x.artistName || 'Untitled';
      var type = x.wrapperType === 'artist' ? 'ARTIST' : (x.collectionType === 'Album' ? 'ALBUM' : 'SONG');
      var artwork = x.artworkUrl100 ? String(x.artworkUrl100).replace('100x100', '300x300') : '';
      return '<article class="discover-card">' +
        (artwork ? '<img src="' + esc(artwork) + '" alt="">' : '<div class="cover">♫</div>') +
        '<div class="discover-meta"><span class="type">' + type + '</span><strong>' + esc(title) + '</strong>' +
        '<small>' + esc(x.artistName || '') + '</small></div><div class="discover-actions">' +
        (x.previewUrl ? '<button class="small-btn" data-preview="' + i + '">▶ Preview</button>' : '') +
        (x.trackViewUrl ? '<a class="small-btn" href="' + esc(x.trackViewUrl) + '" target="_blank" rel="noopener">Open source</a>' : '') +
        '</div></article>';
    }).join('');
    $$('[data-preview]').forEach(function (button) {
      button.onclick = function () {
        var x = items[Number(button.dataset.preview)];
        if (!x || !x.previewUrl) return;
        if (native && typeof native.stopMusic === 'function') native.stopMusic();
        nativePlaying = false;
        currentId = null;
        audio.src = x.previewUrl;
        audio.play().then(function () { setPlayIcon(true); }).catch(function () { toast('Preview could not be played.'); });
        $('#nowTitle').textContent = x.trackName || x.collectionName || 'Preview';
        $('#nowArtist').textContent = (x.artistName || '') + ' · Preview';
      };
    });
  }

  function renderFree() {
    $('#freeDownloadGrid').innerHTML = freeItems.map(function (x, i) {
      return '<article class="discover-card"><div class="cover">⇩</div>' +
        '<div class="discover-meta"><span class="type">FREE / LEGAL DOWNLOAD</span><strong>' + esc(x.title) + '</strong>' +
        '<small>' + esc(x.artist) + '</small></div><div class="discover-actions">' +
        '<button class="small-btn" data-download="' + i + '">⇩ Download MP3</button>' +
        '<button class="small-btn" data-stream="' + i + '">▶ Play</button>' +
        '<a class="small-btn" href="https://archive.org/details/' + encodeURIComponent(x.identifier) + '" target="_blank" rel="noopener">Source</a>' +
        '</div></article>';
    }).join('');

    $$('[data-download]').forEach(function (button) {
      button.onclick = function () {
        var x = freeItems[Number(button.dataset.download)];
        if (!x) return;
        if (native && typeof native.downloadMusic === 'function') {
          button.textContent = 'Downloading…';
          native.downloadMusic(x.downloadUrl, x.title, 'audio/mpeg');
        } else {
          saveDownload({ title: x.title, artist: x.artist, source: x.source, url: x.downloadUrl });
          window.open(x.downloadUrl, '_blank', 'noopener');
        }
      };
    });

    $$('[data-stream]').forEach(function (button) {
      button.onclick = function () {
        var x = freeItems[Number(button.dataset.stream)];
        if (!x) return;
        if (native && typeof native.stopMusic === 'function') native.stopMusic();
        nativePlaying = false;
        currentId = null;
        audio.src = x.downloadUrl;
        audio.play().then(function () { setPlayIcon(true); }).catch(function () { toast('Could not stream this track.'); });
        $('#nowTitle').textContent = x.title;
        $('#nowArtist').textContent = x.artist + ' · Internet Archive';
      };
    });
  }

  /* ----------------------------------------------------------------- events */

  function addToPlaylist(trackId) {
    var names = Object.keys(playlists);
    if (!names.length) {
      var created = prompt('Name your first playlist');
      if (!created) return;
      playlists[created] = [trackId];
      persist();
      renderPlaylists();
      renderStats();
      return;
    }
    var name = prompt('Add to which playlist?\n' + names.join('\n'), names[0]);
    if (!name || !playlists[name]) return;
    if (playlists[name].indexOf(trackId) === -1) playlists[name].push(trackId);
    persist();
    renderPlaylists();
    toast('Added to ' + name);
  }

  document.addEventListener('click', function (event) {
    var like = event.target.closest('[data-like]');
    if (like) {
      event.stopPropagation();
      var likeId = like.dataset.like;
      if (favorites.has(likeId)) favorites.delete(likeId); else favorites.add(likeId);
      persist();
      renderLibrary();
      renderFavorites();
      updateLike();
      return;
    }
    var more = event.target.closest('[data-more]');
    if (more) {
      event.stopPropagation();
      addToPlaylist(more.dataset.more);
      return;
    }
    var playlist = event.target.closest('[data-playlist]');
    if (playlist) {
      var ids = (playlists[playlist.dataset.playlist] || []).filter(findTrack);
      if (!ids.length) { toast('This playlist is empty.'); return; }
      queue = ids;
      playTrack(ids[0], false);
      return;
    }
    var play = event.target.closest('[data-play]');
    if (play) playTrack(play.dataset.play);
  });

  function setSleepTimer(minutes) {
    clearTimeout(sleepTimeout);
    if (!minutes) return;
    sleepTimeout = setTimeout(function () {
      var track = currentTrack();
      if (isNativeTrack(track)) native.pauseMusic(); else audio.pause();
      setPlayIcon(false);
      toast('Sleep timer stopped playback.');
    }, minutes * 60000);
  }

  function setupEqualizer() {
    var ctx, source, bass, mid, treble;
    function ensure() {
      if (ctx || !(window.AudioContext || window.webkitAudioContext)) return;
      ctx = new (window.AudioContext || window.webkitAudioContext)();
      source = ctx.createMediaElementSource(audio);
      bass = ctx.createBiquadFilter(); bass.type = 'lowshelf'; bass.frequency.value = 200;
      mid = ctx.createBiquadFilter(); mid.type = 'peaking'; mid.frequency.value = 1000; mid.Q.value = 0.7;
      treble = ctx.createBiquadFilter(); treble.type = 'highshelf'; treble.frequency.value = 4000;
      source.connect(bass).connect(mid).connect(treble).connect(ctx.destination);
    }
    audio.addEventListener('play', function () {
      try { ensure(); if (ctx) ctx.resume(); } catch (e) { /* ignore */ }
    });
    [['Bass', function () { return bass; }], ['Mid', function () { return mid; }], ['Treble', function () { return treble; }]]
      .forEach(function (entry) {
        $('#eq' + entry[0]).oninput = function (e) {
          try {
            ensure();
            var filter = entry[1]();
            if (filter) filter.gain.value = Number(e.target.value);
          } catch (err) { /* ignore */ }
        };
      });
  }

  function bindControls() {
    ['#addBtn', '#heroAddBtn', '#emptyAddBtn'].forEach(function (sel) {
      var el = $(sel);
      if (el) el.onclick = function () { fileInput.click(); };
    });
    $('#heroDiscoverBtn').onclick = function () { showView('discover'); };
    fileInput.onchange = function (e) {
      addFiles(Array.prototype.slice.call(e.target.files)).catch(function () { toast('Some files could not be added.'); });
      fileInput.value = '';
    };

    $('#playBtn').onclick = togglePlay;
    $('#nextBtn').onclick = function () { next(false); };
    $('#prevBtn').onclick = prev;
    $('#likeBtn').onclick = function () {
      if (!currentId) return;
      if (favorites.has(currentId)) favorites.delete(currentId); else favorites.add(currentId);
      persist();
      updateLike();
      renderLibrary();
      renderFavorites();
    };
    $('#volume').oninput = function (e) {
      audio.volume = Number(e.target.value);
      if (native && typeof native.setVolume === 'function') native.setVolume(Number(e.target.value));
    };
    audio.volume = 0.8;
    $('#progress').oninput = function (e) { seekTo(Number(e.target.value)); };

    audio.addEventListener('loadedmetadata', function () {
      var track = currentTrack();
      if (track && !track.native && isFinite(audio.duration)) {
        track.duration = audio.duration;
        if (track.blob) putTrack(track);
        renderLibrary();
      }
      $('#duration').textContent = fmt(audio.duration);
    });
    audio.addEventListener('timeupdate', function () {
      if (nativePlaying) return;
      $('#currentTime').textContent = fmt(audio.currentTime);
      $('#progress').value = audio.duration ? (audio.currentTime / audio.duration) * 100 : 0;
    });
    audio.addEventListener('ended', function () { next(true); });
    audio.addEventListener('play', function () { setPlayIcon(true); });
    audio.addEventListener('pause', function () { if (!nativePlaying) setPlayIcon(false); });

    $$('[data-view]').forEach(function (b) { b.onclick = function () { showView(b.dataset.view); }; });
    $$('[data-view-link]').forEach(function (b) { b.onclick = function () { showView(b.dataset.viewLink); }; });
    $$('[data-home-action]').forEach(function (b) {
      b.onclick = function () {
        var action = b.dataset.homeAction;
        if (action === 'playlists') { showView('playlists'); return; }
        libraryTab = action === 'songs' ? 'songs' : action;
        $$('.library-tab').forEach(function (tab) { tab.classList.toggle('active', tab.dataset.libraryTab === libraryTab); });
        showView('library');
      };
    });
    $$('.library-tab').forEach(function (b) {
      b.onclick = function () {
        $$('.library-tab').forEach(function (x) { x.classList.remove('active'); });
        b.classList.add('active');
        libraryTab = b.dataset.libraryTab;
        renderLibrary();
      };
    });
    $$('.discover-tab').forEach(function (b) {
      b.onclick = function () {
        $$('.discover-tab').forEach(function (x) { x.classList.remove('active'); });
        b.classList.add('active');
        discoverType = b.dataset.type;
        renderDiscover();
      };
    });

    $('#searchInput').onkeydown = function (e) { if (e.key === 'Enter') discover(e.target.value); };
    $('#searchInput').oninput = function () { renderLibrary(); };
    $('#searchBtn').onclick = function () { discover($('#searchInput').value); };

    $('#newPlaylistBtn').onclick = function () {
      var name = prompt('Playlist name');
      if (!name) return;
      playlists[name] = playlists[name] || [];
      persist();
      renderPlaylists();
      renderStats();
    };
    $('#rescanBtn').onclick = function () {
      if (native && typeof native.requestAudioPermission === 'function') native.requestAudioPermission();
      var count = scanPhoneMusic();
      toast(count ? count + ' song' + (count === 1 ? '' : 's') + ' on this phone' : 'No new music found.');
    };
    $('#queueBtn').onclick = function () { $('#queueDrawer').classList.toggle('open'); renderQueue(); };
    $('#closeQueue').onclick = function () { $('#queueDrawer').classList.remove('open'); };
    $('#shuffleBtn').onchange = function (e) {
      shuffle = e.target.checked;
      $('#shufflePlayer').classList.toggle('on', shuffle);
      persist();
    };
    $('#repeatBtn').onchange = function (e) { repeat = e.target.checked; persist(); };
    $('#shufflePlayer').onclick = function () { $('#shuffleBtn').click(); };
    $('#sleepTimer').onchange = function (e) { setSleepTimer(Number(e.target.value)); };
    $('#clearHistoryBtn').onclick = function () { history = []; persist(); renderRecent(); renderLibrary(); };
    $('#themeBtn').onclick = function () { document.body.classList.toggle('light'); };
  }

  /* ------------------------------------------------------------------- boot */

  restore();
  bindControls();
  setupEqualizer();
  $('#shuffleBtn').checked = shuffle;
  $('#repeatBtn').checked = repeat;
  $('#shufflePlayer').classList.toggle('on', shuffle);
  renderAll();

  initDB()
    .then(loadLibrary)
    .catch(function () { renderAll(); });

  window.addEventListener('error', function (e) {
    console.error('Universal MP3 Player error', e.message);
  });
})();
