package com.arawn.scanner.report

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Produces a fully self-contained HTML5 report from [ReportData].
 *
 * All CSS and JavaScript are embedded inline — the file can be opened from
 * local storage with no server or internet required (map tiles are the only
 * exception; they stream from OpenStreetMap via the same INTERNET permission
 * that the app's live map already uses, and gracefully degrade if offline).
 *
 * Output sections: summary cards → analysis insights → six canvas charts →
 * interactive Leaflet map → searchable/sortable/paginated Wi-Fi + BLE tables.
 */
object HtmlReportRenderer {

    fun render(data: ReportData): String = buildString {
        append("<!DOCTYPE html><html lang=\"en\"><head>")
        append("<meta charset=\"UTF-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>ARAWN Report — Session #${data.session.sessionId}</title>")
        append("<style>"); append(CSS); append("</style>")
        append("</head><body>")
        append(buildBody(data))
        append("</body></html>")
    }

    // ── Body ────────────────────────────────────────────────────────────────

    private fun buildBody(data: ReportData): String = buildString {
        val uniqueSsids = data.wifi.count { it.ssid.isNotEmpty() }.let {
            data.wifi.map { w -> w.ssid }.filter { it.isNotEmpty() }.toSet().size
        }
        val strongestRssi = data.wifi.maxOfOrNull { it.bestRssi }
        val openNets = data.wifi.count { it.securityType == "Open" }

        // ── Header
        append("""<div class="rpt-header"><div class="container">
<div class="rpt-title">ARAWN // RF SURVEY REPORT</div>
<div class="rpt-sub">Session #${data.session.sessionId} &nbsp;·&nbsp; Started: ${ts(data.session.startMs)} &nbsp;·&nbsp; ${if (data.session.endMs != null) "Duration: ${dur(data.session.durationMs)}" else "In Progress"} &nbsp;·&nbsp; ${data.meta.deviceModel} &nbsp;·&nbsp; Android ${data.meta.androidVersion}</div>
</div></div>""")

        append("""<div class="container">""")

        // ── Summary cards
        append("""<div class="stats-grid">
${statCard(data.wifi.size.toString(), "Wi-Fi Networks")}
${statCard(data.ble.size.toString(), "BLE Devices")}
${statCard(uniqueSsids.toString(), "Unique SSIDs")}
${statCard(if (strongestRssi != null) "$strongestRssi dBm" else "—", "Strongest Signal")}
${statCard(dur(data.session.durationMs), "Duration")}
${statCard(data.session.pointsCollected.toString(), "GPS Fixes")}
${statCard(openNets.toString(), "Open Networks")}
${statCard(data.meta.totalRecords.toString(), "Total Records")}
</div>""")

        // ── Analysis
        append("""<div class="card"><div class="section-title">Analysis</div>
<div class="insight-grid" id="analysis-grid"></div></div>""")

        // ── Charts
        append("""<div class="charts-grid">
<div class="chart-card"><div class="chart-title">2.4 GHz Channel Usage</div><canvas id="chart-channels"></canvas></div>
<div class="chart-card"><div class="chart-title">Security Distribution</div><canvas id="chart-security"></canvas></div>
<div class="chart-card"><div class="chart-title">Frequency Bands</div><canvas id="chart-bands"></canvas></div>
<div class="chart-card"><div class="chart-title">Signal Strength Distribution</div><canvas id="chart-rssi"></canvas></div>
<div class="chart-card"><div class="chart-title">Top Vendors (Wi-Fi)</div><canvas id="chart-vendors"></canvas></div>
<div class="chart-card"><div class="chart-title">Device Types</div><canvas id="chart-classes"></canvas></div>
<div class="chart-card"><div class="chart-title">Wi-Fi Discovery Over Time</div><canvas id="chart-timeline"></canvas></div>
</div>""")

        // ── Map
        append("""<div class="card"><div class="section-title">Track Map
<span class="map-legend"><span class="dot dot-wifi"></span>Wi-Fi <span class="dot dot-ble"></span>BLE <span class="dot dot-track"></span>Path</span>
</div><div id="map"></div></div>""")

        // ── Data tables (tabs)
        append("""<div class="card">
<div class="tab-bar">
<button class="tab-btn active" data-tab="tab-wifi">Wi-Fi (${data.wifi.size})</button>
<button class="tab-btn" data-tab="tab-ble">Bluetooth (${data.ble.size})</button>
</div>
<div class="tab-pane" id="tab-wifi"><div id="wifi-tbl"></div></div>
<div class="tab-pane" id="tab-ble" style="display:none"><div id="ble-tbl"></div></div>
</div>""")

        // ── Footer
        append("""<div class="rpt-footer">ARAWN v${data.meta.appVersion} &nbsp;·&nbsp; Session #${data.meta.sessionId} &nbsp;·&nbsp; Exported ${ts(data.meta.exportMs)} &nbsp;·&nbsp; ${data.meta.deviceModel} · Android ${data.meta.androidVersion} &nbsp;·&nbsp; ${data.meta.totalRecords} records</div>""")

        append("</div>") // /container

        // ── Inline data + scripts (data first so JS can reference it)
        append("<script>const REPORT_DATA="); append(buildJson(data)); append(";</script>")
        append("<script>"); append(JS); append("</script>")
        // Leaflet for the map; graceful fallback built into initMap() when offline
        append("""<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script src="https://unpkg.com/leaflet.markercluster@1.5.3/dist/leaflet.markercluster-src.js"></script>
<link rel="stylesheet" href="https://unpkg.com/leaflet.markercluster@1.5.3/dist/MarkerCluster.css">
<link rel="stylesheet" href="https://unpkg.com/leaflet.markercluster@1.5.3/dist/MarkerCluster.Default.css">
<script>initMap();</script>""")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun statCard(value: String, label: String) =
        """<div class="stat-card"><div class="stat-value">${value.esc()}</div><div class="stat-label">${label.esc()}</div></div>"""

    private fun buildJson(data: ReportData) = buildString {
        append("{\"session\":{\"id\":${data.session.sessionId},\"startMs\":${data.session.startMs},")
        append("\"endMs\":${data.session.endMs ?: "null"},\"duration\":${data.session.durationMs},")
        append("\"points\":${data.session.pointsCollected}},")

        append("\"wifi\":[")
        data.wifi.forEachIndexed { i, w ->
            if (i > 0) append(',')
            append("{\"ssid\":${js(w.ssid)},\"bssid\":${js(w.bssid)},\"rssi\":${w.bestRssi},")
            append("\"freq\":${w.frequencyMhz},\"channel\":${w.channel},\"band\":${js(w.band)},")
            append("\"security\":${js(w.securityType)},\"vendor\":${js(w.vendor)},")
            append("\"cls\":${js(w.deviceClass)},\"conf\":${w.classConfidence},\"cstat\":${js(w.classStatus)},")
            append("\"lat\":${c(w.lat)},\"lon\":${c(w.lon)},")
            append("\"firstSeen\":${w.firstSeenMs},\"lastSeen\":${w.lastSeenMs},\"seenCount\":${w.seenCount}}")
        }
        append("],\"ble\":[")
        data.ble.forEachIndexed { i, b ->
            if (i > 0) append(',')
            append("{\"name\":${js(b.name)},\"mac\":${js(b.mac)},\"rssi\":${b.bestRssi},")
            append("\"vendor\":${js(b.vendor)},")
            append("\"cls\":${js(b.deviceClass)},\"conf\":${b.classConfidence},\"cstat\":${js(b.classStatus)},")
            append("\"lat\":${c(b.lat)},\"lon\":${c(b.lon)},")
            append("\"firstSeen\":${b.firstSeenMs},\"lastSeen\":${b.lastSeenMs},\"seenCount\":${b.seenCount}}")
        }
        append("],\"track\":[")
        data.track.forEachIndexed { i, t ->
            if (i > 0) append(',')
            append("{\"lat\":${c(t.lat)},\"lon\":${c(t.lon)},\"ts\":${t.tsMs}}")
        }
        append("],\"meta\":{\"appVersion\":${js(data.meta.appVersion)},")
        append("\"deviceModel\":${js(data.meta.deviceModel)},")
        append("\"androidVersion\":${js(data.meta.androidVersion)},")
        append("\"exportMs\":${data.meta.exportMs},\"sessionId\":${data.meta.sessionId},")
        append("\"totalRecords\":${data.meta.totalRecords}}}")
    }

    private fun ts(ms: Long): String = TIME_FMT.format(Date(ms))
    private fun dur(ms: Long): String {
        val s = ms / 1000; val m = s / 60; val h = m / 60
        return if (h > 0) "${h}h ${m % 60}m" else if (m > 0) "${m}m ${s % 60}s" else "${s}s"
    }
    private fun String.esc() = replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun c(v: Double) = if (v.isFinite()) "%.6f".format(Locale.US, v) else "0"
    private fun js(s: String?) = if (s == null) "null" else
        "\"" + s.replace("\\","\\\\").replace("\"","\\\"")
            .replace("\n","\\n").replace("\r","\\r")
            .replace("\t","\\t").replace("</","<\\/") + "\""

    private val TIME_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ── CSS ─────────────────────────────────────────────────────────────────

    private val CSS = """
:root{--bg:#0a0a0a;--card:#111;--border:#1e1e1e;--green:#35d07f;--amber:#e0b341;--cyan:#4fc3f7;--red:#ef5350;--text:#e6e6e6;--dim:#6e6e6e;--font:'Courier New',Courier,monospace}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--text);font-family:var(--font);font-size:13px;line-height:1.5}
a{color:var(--cyan)}
.container{max-width:1200px;margin:0 auto;padding:16px}
.rpt-header{background:#111;border-bottom:1px solid var(--border);padding:16px 20px}
.rpt-title{color:var(--amber);font-size:18px;letter-spacing:2px;font-weight:bold}
.rpt-sub{color:var(--dim);font-size:11px;margin-top:6px}
.rpt-footer{text-align:center;color:var(--dim);font-size:10px;padding:20px;border-top:1px solid var(--border);margin-top:20px}
.card{background:var(--card);border:1px solid var(--border);border-radius:6px;padding:16px;margin-bottom:16px}
.section-title{color:var(--amber);font-size:12px;text-transform:uppercase;letter-spacing:1px;margin-bottom:12px;display:flex;align-items:center;gap:12px}
.stats-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:10px;margin-bottom:16px}
.stat-card{background:var(--card);border:1px solid var(--border);border-radius:6px;padding:14px;text-align:center}
.stat-value{font-size:22px;color:var(--green);font-weight:bold;line-height:1.2}
.stat-label{color:var(--dim);font-size:10px;margin-top:4px;text-transform:uppercase}
.charts-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:12px;margin-bottom:16px}
.chart-card{background:var(--card);border:1px solid var(--border);border-radius:6px;padding:14px}
.chart-title{color:var(--amber);font-size:10px;text-transform:uppercase;letter-spacing:1px;margin-bottom:8px}
canvas{display:block;width:100%}
#map{height:420px;border-radius:4px;background:#0d0d0d}
.map-legend{color:var(--dim);font-size:10px;display:flex;align-items:center;gap:8px;margin-left:auto}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:2px}
.dot-wifi{background:var(--cyan)}.dot-ble{background:var(--red)}.dot-track{background:var(--green)}
.tab-bar{display:flex;gap:6px;margin-bottom:14px;border-bottom:1px solid var(--border);padding-bottom:8px}
.tab-btn{background:none;border:1px solid transparent;color:var(--dim);padding:5px 14px;border-radius:4px;cursor:pointer;font-family:var(--font);font-size:11px}
.tab-btn.active{border-color:var(--green);color:var(--green);background:#0d1a14}
.tbl-ctrl{display:flex;gap:8px;margin-bottom:10px;flex-wrap:wrap;align-items:center}
.search-inp{background:#1a1a1a;border:1px solid var(--border);color:var(--text);padding:6px 10px;border-radius:4px;font-family:var(--font);font-size:12px;flex:1;min-width:160px}
.search-inp:focus{outline:none;border-color:var(--green)}
.tbl-info{color:var(--dim);font-size:11px;white-space:nowrap}
.tbl-wrap{overflow-x:auto}
table{width:100%;border-collapse:collapse;font-size:11px}
th{color:var(--amber);text-align:left;padding:7px 6px;border-bottom:1px solid var(--border);cursor:pointer;user-select:none;white-space:nowrap}
th:hover{color:var(--green)}
th.asc::after{content:" ↑"}th.desc::after{content:" ↓"}
td{padding:5px 6px;border-bottom:1px solid #161616;vertical-align:middle;white-space:nowrap}
tr:hover td{background:#161616}
.badge{display:inline-block;padding:2px 6px;border-radius:3px;font-size:10px;font-weight:bold}
.b-open{background:#3d1a1a;color:#ef5350}.b-wpa3{background:#0d3a26;color:#35d07f}
.b-wpa2{background:#1a2e0d;color:#8bc34a}.b-wpa{background:#2a2a0d;color:#e0b341}
.b-wep{background:#2a1a00;color:#ff9800}.b-owe{background:#0d2a3a;color:#4fc3f7}
.rssi-bar{display:inline-block;height:7px;border-radius:2px;vertical-align:middle;margin-right:5px}
.pgn{display:flex;gap:4px;margin-top:10px;flex-wrap:wrap;align-items:center}
.pg{background:#1a1a1a;border:1px solid var(--border);color:var(--text);padding:3px 7px;border-radius:3px;cursor:pointer;font-family:var(--font);font-size:11px}
.pg:hover,.pg.on{border-color:var(--green);color:var(--green)}
.pg-info{color:var(--dim);font-size:11px}
.insight-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:10px;margin-top:8px}
.ins{background:#0d0d0d;border:1px solid var(--border);border-radius:4px;padding:10px}
.ins-lbl{color:var(--dim);font-size:10px;text-transform:uppercase}
.ins-val{margin-top:4px;font-size:13px}
.ins-val.warn{color:#ef5350}.ins-val.good{color:#35d07f}
@media(max-width:600px){.stats-grid{grid-template-columns:repeat(2,1fr)}.charts-grid{grid-template-columns:1fr}}
""".trimIndent()

    // ── JavaScript ──────────────────────────────────────────────────────────
    // Dollar signs in JS are written as ${'$'} to prevent Kotlin string
    // interpolation from misreading them.

    private val JS = """
(function(){
'use strict';

// ── Utilities
function gid(id){return document.getElementById(id);}
function fmt(ms){return ms?new Date(ms).toLocaleString():'';}
function rssiColor(v){return v>=-50?'#35d07f':v>=-70?'#e0b341':'#ef5350';}
function rssiW(v){return Math.max(4,Math.min(56,((v+100)/70)*56));}
function secBadge(s){var m={Open:'open',WPA3:'wpa3',WPA2:'wpa2',WPA:'wpa',WEP:'wep',OWE:'owe'};return '<span class="badge b-'+(m[s]||'wpa2')+'">'+s+'</span>';}
function coord(la,lo){return(la&&lo)?la.toFixed(5)+','+lo.toFixed(5):'';}

// ── Tab switching
function initTabs(){
  document.querySelectorAll('.tab-btn').forEach(function(btn){
    btn.onclick=function(){
      var t=btn.dataset.tab;
      document.querySelectorAll('.tab-btn').forEach(function(b){b.classList.remove('active');});
      document.querySelectorAll('.tab-pane').forEach(function(p){p.style.display='none';});
      btn.classList.add('active');
      gid(t).style.display='';
    };
  });
}

// ── DataTable
function DataTable(cid,rows,cols,ps){
  ps=ps||25;
  var el=gid(cid),all=rows,fil=rows,sc=-1,sa=true,pg=0;
  function search(q){var lq=q.toLowerCase();fil=lq?all.filter(function(r){return r.some(function(v){return String(v||'').toLowerCase().indexOf(lq)>=0;});}):all;pg=0;renderBody();renderPager();}
  function sort(i){if(sc===i)sa=!sa;else{sc=i;sa=true;}fil=fil.slice().sort(function(a,b){var av=a[i],bv=b[i],c=typeof av==='number'?av-bv:String(av||'').localeCompare(String(bv||''));return sa?c:-c;});pg=0;renderBody();renderPager();updateH();}
  var wrap,tbody,pager,thead2;
  function render(){
    el.innerHTML='';
    var ctrl=document.createElement('div');ctrl.className='tbl-ctrl';
    var inp=document.createElement('input');inp.type='search';inp.className='search-inp';inp.placeholder='Search '+all.length+' records…';inp.oninput=function(e){search(e.target.value);};
    ctrl.appendChild(inp);
    var inf=document.createElement('span');inf.className='tbl-info';inf.id=cid+'-inf';ctrl.appendChild(inf);
    el.appendChild(ctrl);
    wrap=document.createElement('div');wrap.className='tbl-wrap';
    var tbl=document.createElement('table');
    thead2=tbl.createTHead();var hr=thead2.insertRow();
    cols.forEach(function(col,i){var th=document.createElement('th');th.textContent=col.label;th.dataset.i=i;th.onclick=function(){sort(i);};hr.appendChild(th);});
    tbody=tbl.createTBody();wrap.appendChild(tbl);el.appendChild(wrap);
    pager=document.createElement('div');pager.className='pgn';el.appendChild(pager);
    renderBody();renderPager();
  }
  function renderBody(){
    tbody.innerHTML='';
    var s=pg*ps,slice=fil.slice(s,s+ps);
    slice.forEach(function(row){
      var tr=tbody.insertRow();
      cols.forEach(function(col,i){var td=tr.insertCell();td.innerHTML=col.r?col.r(row[i],row):String(row[i]!=null?row[i]:'');});
    });
    var inf2=gid(cid+'-inf');
    if(inf2){var s2=pg*ps+1,e=Math.min((pg+1)*ps,fil.length);inf2.textContent=fil.length?s2+'–'+e+' of '+fil.length:'';}
  }
  function renderPager(){
    pager.innerHTML='';
    var tot=Math.ceil(fil.length/ps);if(tot<=1)return;
    if(pg>0)btn('‹',pg-1);
    for(var p=Math.max(0,pg-2);p<=Math.min(tot-1,pg+2);p++)btn(p+1,p,p===pg);
    if(pg<tot-1)btn('›',pg+1);
  }
  function btn(lbl,p,on){var b=document.createElement('button');b.textContent=lbl;b.className='pg'+(on?' on':'');var cp=p;b.onclick=function(){pg=cp;renderBody();renderPager();};pager.appendChild(b);}
  function updateH(){thead2.querySelectorAll('th').forEach(function(th){th.classList.remove('asc','desc');if(parseInt(th.dataset.i)===sc)th.classList.add(sa?'asc':'desc');});}
  render();
}

// ── Charts (vanilla Canvas)
function cw(id){var c=gid(id);if(!c)return null;c.width=c.parentElement.clientWidth||300;return c;}

function drawBarH(id,labels,values,color,maxItems){
  var canvas=cw(id);if(!canvas||!labels.length)return;
  color=color||'#35d07f';maxItems=maxItems||10;
  var pairs=labels.map(function(l,i){return[l,values[i]];}).sort(function(a,b){return b[1]-a[1];}).slice(0,maxItems);
  var mx=Math.max.apply(null,pairs.map(function(p){return p[1];}));if(!mx)return;
  var rH=22,pL=115,pR=46,pT=6,pB=6;
  canvas.height=pairs.length*rH+pT+pB;
  var ctx=canvas.getContext('2d');ctx.clearRect(0,0,canvas.width,canvas.height);
  var w=canvas.width-pL-pR;
  pairs.forEach(function(pair,i){
    var y=pT+i*rH,bw=(pair[1]/mx)*w;
    ctx.fillStyle='#1a1a1a';ctx.fillRect(pL,y+3,w,rH-6);
    ctx.fillStyle=color;ctx.fillRect(pL,y+3,bw,rH-6);
    ctx.fillStyle='#e6e6e6';ctx.font='10px monospace';ctx.textAlign='right';
    ctx.fillText(String(pair[0]).slice(0,17),pL-5,y+rH/2+3.5);
    ctx.fillStyle='#6e6e6e';ctx.textAlign='left';
    ctx.fillText(pair[1],pL+bw+4,y+rH/2+3.5);
  });
}

function drawPie(id,labels,values,colors){
  var canvas=cw(id);if(!canvas||!labels.length)return;
  var total=values.reduce(function(a,b){return a+b;},0);if(!total)return;
  var sz=Math.min(canvas.width*0.55,180),pad=8;
  canvas.height=sz+pad*2;
  var ctx=canvas.getContext('2d');ctx.clearRect(0,0,canvas.width,canvas.height);
  var cx=sz/2+pad,cy=sz/2+pad,r=sz/2-pad,angle=-Math.PI/2;
  values.forEach(function(v,i){
    var sl=(v/total)*2*Math.PI;
    ctx.beginPath();ctx.moveTo(cx,cy);ctx.arc(cx,cy,r,angle,angle+sl);
    ctx.fillStyle=colors[i%colors.length];ctx.fill();
    ctx.strokeStyle='#0a0a0a';ctx.lineWidth=2;ctx.stroke();
    angle+=sl;
  });
  var lx=sz+pad*2+8,ly=pad+12;
  labels.forEach(function(l,i){
    ctx.fillStyle=colors[i%colors.length];ctx.fillRect(lx,ly-9,10,10);
    ctx.fillStyle='#e6e6e6';ctx.font='10px monospace';ctx.textAlign='left';
    ctx.fillText(l.slice(0,14)+': '+values[i],lx+13,ly);
    ly+=15;
  });
}

function drawHistogram(id,values,buckets){
  var canvas=cw(id);if(!canvas||!values.length)return;
  buckets=buckets||8;
  var mn=Math.min.apply(null,values),mx=Math.max.apply(null,values),rng=mx-mn||1,step=rng/buckets;
  var counts=new Array(buckets).fill(0);
  values.forEach(function(v){counts[Math.min(Math.floor((v-mn)/step),buckets-1)]++;});
  var labels=counts.map(function(_,i){return Math.round(mn+i*step)+'';});
  drawBarH(id,labels,counts,'#4fc3f7',buckets);
}

// ── Analysis section
function buildAnalysis(){
  var el=gid('analysis-grid');if(!el)return;
  var wifi=REPORT_DATA.wifi,ble=REPORT_DATA.ble;
  var open=wifi.filter(function(w){return w.security==='Open';});
  var wpa3=wifi.filter(function(w){return w.security==='WPA3';});
  var hidden=wifi.filter(function(w){return!w.ssid;});
  var ssidC={};wifi.forEach(function(w){if(w.ssid)ssidC[w.ssid]=(ssidC[w.ssid]||0)+1;});
  var dups=Object.entries(ssidC).filter(function(e){return e[1]>1;}).sort(function(a,b){return b[1]-a[1];});
  var chC={};wifi.forEach(function(w){if(w.channel)chC[w.channel]=(chC[w.channel]||0)+1;});
  var cong=Object.entries(chC).filter(function(e){return e[1]>3;});
  var vC={};wifi.forEach(function(w){if(w.vendor&&w.vendor!=='Unknown'&&w.vendor!=='Unknown Vendor')vC[w.vendor]=(vC[w.vendor]||0)+1;});
  var topV=Object.entries(vC).sort(function(a,b){return b[1]-a[1];})[0];
  var allD=[].concat(wifi,ble);
  var classified=allD.filter(function(d){return d.cls&&d.cls!=='Unknown';});
  var clsTypes={};classified.forEach(function(d){clsTypes[d.cls]=1;});
  var items=[
    {l:'Open Networks',v:open.length,c:open.length>0?'warn':'good'},
    {l:'WPA3 Networks',v:wpa3.length,c:wpa3.length>0?'good':''},
    {l:'Hidden SSIDs',v:hidden.length,c:''},
    {l:'Duplicate SSIDs',v:dups.length,c:''},
    {l:'Congested Channels',v:cong.length,c:cong.length>2?'warn':''},
    {l:'Top Vendor',v:topV?topV[0].slice(0,20)+' ('+topV[1]+')':'N/A',c:''},
    {l:'Devices Classified',v:classified.length+' / '+allD.length+' ('+Object.keys(clsTypes).length+' types)',c:classified.length>0?'good':''},
    {l:'Most Common SSID',v:dups[0]?'"'+dups[0][0].slice(0,18)+'" x'+dups[0][1]:'N/A',c:''},
    {l:'BLE Unnamed',v:ble.filter(function(b){return b.name==='(unnamed)';}).length,c:''},
  ];
  el.innerHTML=items.map(function(it){return '<div class="ins"><div class="ins-lbl">'+it.l+'</div><div class="ins-val '+it.c+'">'+it.v+'</div></div>';}).join('');
}

// ── Charts
function buildCharts(){
  var wifi=REPORT_DATA.wifi;
  var PAL=['#35d07f','#e0b341','#4fc3f7','#ef5350','#ab47bc','#26c6da','#ff7043','#66bb6a'];

  var chM={};wifi.filter(function(w){return w.band==='2.4 GHz';}).forEach(function(w){chM[w.channel]=(chM[w.channel]||0)+1;});
  var chK=Object.keys(chM).sort(function(a,b){return a-b;});
  drawBarH('chart-channels',chK,chK.map(function(k){return chM[k];}), '#e0b341');

  var secM={};wifi.forEach(function(w){secM[w.security]=(secM[w.security]||0)+1;});
  drawPie('chart-security',Object.keys(secM),Object.values(secM),PAL);

  var bandM={};wifi.forEach(function(w){bandM[w.band]=(bandM[w.band]||0)+1;});
  drawPie('chart-bands',Object.keys(bandM),Object.values(bandM),['#35d07f','#4fc3f7','#e0b341','#ab47bc']);

  drawHistogram('chart-rssi',wifi.map(function(w){return w.rssi;}));

  var vM={};wifi.forEach(function(w){if(w.vendor&&w.vendor!=='Unknown'&&w.vendor!=='Unknown Vendor')vM[w.vendor]=(vM[w.vendor]||0)+1;});
  var vK=Object.keys(vM).sort(function(a,b){return vM[b]-vM[a];}).slice(0,8);
  drawBarH('chart-vendors',vK,vK.map(function(k){return vM[k];}), '#4fc3f7');

  // Device types across BOTH radios; unclassified rows are excluded so the
  // chart shows only positively-identified categories.
  var clsM={};[].concat(REPORT_DATA.wifi,REPORT_DATA.ble).forEach(function(d){
    if(d.cls&&d.cls!=='Unknown')clsM[d.cls]=(clsM[d.cls]||0)+1;});
  var clsK=Object.keys(clsM).sort(function(a,b){return clsM[b]-clsM[a];});
  drawBarH('chart-classes',clsK,clsK.map(function(k){return clsM[k];}), '#35d07f');

  if(wifi.length>1){
    var sorted=wifi.slice().sort(function(a,b){return a.firstSeen-b.firstSeen;});
    var t0=sorted[0].firstSeen,t1=sorted[sorted.length-1].firstSeen,step=Math.max((t1-t0)/10,60000);
    var tl=[],tv=[];
    for(var t=t0;t<=t1+step;t+=step){
      tl.push(new Date(t).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'}));
      tv.push(sorted.filter(function(w){return w.firstSeen<=t;}).length);
    }
    drawBarH('chart-timeline',tl,tv,'#ab47bc',tl.length);
  }
}

// ── Tables
function buildTables(){
  var rssiCell=function(v){return '<span class="rssi-bar" style="width:'+rssiW(v)+'px;background:'+rssiColor(v)+'"></span>'+v+' dBm';};
  // "Smart Bulb · 87%" with a dim qualifier for ambiguous/low-confidence calls.
  var classOf=function(d){
    if(!d.cls||d.cls==='Unknown')return '<span style="color:#666">—</span>';
    var q=(d.cstat==='AMBIGUOUS'||d.cstat==='LOW_CONFIDENCE')?' <span style="color:#e0b341">?</span>':'';
    return d.cls+' <span style="color:#888">'+d.conf+'%</span>'+q;
  };

  new DataTable('wifi-tbl',
    REPORT_DATA.wifi.map(function(w){return[w.ssid||'(hidden)',w.bssid,w.rssi,w.freq,w.channel,w.band,w.security,w.vendor,classOf(w),coord(w.lat,w.lon),fmt(w.firstSeen),fmt(w.lastSeen),w.seenCount];}),
    [{label:'SSID'},{label:'BSSID'},{label:'RSSI',r:rssiCell},{label:'MHz'},{label:'Ch'},{label:'Band'},{label:'Security',r:secBadge},{label:'Vendor'},{label:'Class'},{label:'Coordinates'},{label:'First Seen'},{label:'Last Seen'},{label:'Seen x'}]);

  new DataTable('ble-tbl',
    REPORT_DATA.ble.map(function(b){return[b.name,b.mac,b.rssi,b.vendor,classOf(b),coord(b.lat,b.lon),fmt(b.firstSeen),fmt(b.lastSeen),b.seenCount];}),
    [{label:'Name'},{label:'MAC'},{label:'RSSI',r:rssiCell},{label:'Vendor'},{label:'Class'},{label:'Coordinates'},{label:'First Seen'},{label:'Last Seen'},{label:'Seen x'}]);
}

// ── Map
function initMap(){
  var mapEl=gid('map');if(!mapEl)return;
  if(typeof L==='undefined'){
    mapEl.style.cssText='display:flex;align-items:center;justify-content:center;color:#6e6e6e;font-size:12px;';
    mapEl.textContent='Map unavailable — open this report with internet access to load tiles';
    return;
  }
  var map=L.map('map');
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{attribution:'© OpenStreetMap contributors',maxZoom:19}).addTo(map);
  var track=REPORT_DATA.track,wifi=REPORT_DATA.wifi,ble=REPORT_DATA.ble;
  if(track.length>1){var pts=track.map(function(t){return[t.lat,t.lon];});L.polyline(pts,{color:'#35d07f',weight:3,opacity:0.75}).addTo(map);}
  var hasCluster=typeof L.markerClusterGroup!=='undefined';
  var wG=hasCluster?L.markerClusterGroup({maxClusterRadius:50}):L.layerGroup();
  var bG=hasCluster?L.markerClusterGroup({maxClusterRadius:50}):L.layerGroup();
  var mkW=L.divIcon({className:'',html:'<div style="width:8px;height:8px;background:#4fc3f7;border-radius:50%;border:1px solid #000;opacity:.9"></div>',iconSize:[8,8]});
  var mkB=L.divIcon({className:'',html:'<div style="width:8px;height:8px;background:#ef5350;border-radius:50%;border:1px solid #000;opacity:.9"></div>',iconSize:[8,8]});
  wifi.forEach(function(w){if(!w.lat||!w.lon)return;L.marker([w.lat,w.lon],{icon:mkW}).bindPopup('<b>'+(w.ssid||'(hidden)')+'</b><br>'+w.bssid+'<br>'+w.rssi+' dBm &nbsp;'+w.security+'<br><small>'+w.vendor+'</small>').addTo(wG);});
  ble.forEach(function(b){if(!b.lat||!b.lon)return;L.marker([b.lat,b.lon],{icon:mkB}).bindPopup('<b>'+b.name+'</b><br>'+b.mac+'<br>'+b.rssi+' dBm<br><small>'+b.vendor+'</small>').addTo(bG);});
  map.addLayer(wG);map.addLayer(bG);
  var all=track.filter(function(t){return t.lat&&t.lon;}).map(function(t){return[t.lat,t.lon];});
  if(all.length)map.fitBounds(L.latLngBounds(all).pad(0.12));else map.setView([0,0],2);
}

// ── Boot
document.addEventListener('DOMContentLoaded',function(){
  initTabs();
  buildAnalysis();
  buildCharts();
  buildTables();
});

})();
""".trimIndent()
}
