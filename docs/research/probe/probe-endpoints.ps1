# PriceLens 信息源端点实测（2026-11-08）——只读探测，不修改任何 app 代码
# 兼容 Windows PowerShell 5.1
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
[Net.ServicePointManager]::Expect100Continue = $false
$ErrorActionPreference = 'Continue'

$OUT_DIR = 'E:\项目\PriceLens\docs\research\probe'
New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null
$results = New-Object System.Collections.ArrayList

$MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; PLB110) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.0.0 Mobile Safari/537.36'
$BROWSER_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36'
$BOT_UA = 'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)'

function GBKEnc([string]$s) {
  $bytes = [Text.Encoding]::GetEncoding('GBK').GetBytes($s)
  ($bytes | ForEach-Object { '%{0:X2}' -f $_ }) -join ''
}

$BLOCK_PAT = '京东验证|验证码|滑块|captcha|安全验证|robot|风控|Access Denied|请开启 ?JavaScript|403 Forbidden|Forbidden|访问异常|人机'

function Probe([string]$name, [string]$method, [string]$url, $body, [string]$ua, [string]$referer, [string]$cookie) {
  $row = [ordered]@{ name = $name; method = $method; url = $url; status = ''; ms = 0; bytes = 0; ctype = ''; finalUrl = ''; blockedHint = ''; snippet = ''; note = '' }
  $sw = [Diagnostics.Stopwatch]::StartNew()
  try {
    $params = @{
      Uri = $url; Method = $method; TimeoutSec = 8; UseBasicParsing = $true
      MaximumRedirection = 3; ErrorAction = 'Stop'
    }
    if ($ua) { $params['UserAgent'] = $ua }
    $h = @{}
    if ($referer) { $h['Referer'] = $referer }
    if ($cookie) { $h['Cookie'] = $cookie }
    if ($h.Count -gt 0) { $params['Headers'] = $h }
    if ($body) { $params['Body'] = $body; $params['ContentType'] = 'application/json; charset=utf-8' }
    $r = Invoke-WebRequest @params
    $sw.Stop()
    $row.ms = [int]$sw.ElapsedMilliseconds
    $row.status = [int]$r.StatusCode
    try { $row.ctype = [string]$r.Headers['Content-Type'] } catch { }
    try { $row.finalUrl = [string]$r.BaseResponse.ResponseUri.AbsoluteUri } catch { }
    $c = $r.Content
    if ($c -is [byte[]]) { $c = [Text.Encoding]::UTF8.GetString($c) }
    $text = [regex]::Replace([string]$c, '\s+', ' ')
    $row.bytes = $text.Length
    $row.snippet = $text.Substring(0, [Math]::Min(200, $text.Length))
  } catch {
    $sw.Stop()
    $row.ms = [int]$sw.ElapsedMilliseconds
    $resp = $_.Exception.Response
    if ($resp -ne $null) {
      try { $row.status = [int]$resp.StatusCode } catch { $row.status = 'ERR' }
      try { $row.finalUrl = [string]$resp.ResponseUri.AbsoluteUri } catch { }
      try {
        $st = $resp.GetResponseStream()
        $sr = New-Object IO.StreamReader($st)
        $bodyText = $sr.ReadToEnd()
        $row.snippet = ([regex]::Replace($bodyText, '\s+', ' '))
        $row.snippet = $row.snippet.Substring(0, [Math]::Min(200, $row.snippet.Length))
      } catch { }
    } else {
      $row.status = 'ERR'
      $row.note = $_.Exception.Message
      if ($_.Exception.Message -match '超时|timed out|timeout') { $row.note = 'TIMEOUT: ' + $_.Exception.Message }
    }
  }
  $probeText = ('' + $row.snippet + ' ' + $row.note)
  if ($probeText -match $BLOCK_PAT) { $row.blockedHint = 'SUSPECT_BLOCK' }
  [void]$results.Add([pscustomobject]$row)
  Write-Output ("[{0}] {1} {2} => status={3} {4}ms {5}B block={6}" -f $name, $method, $url, $row.status, $row.ms, $row.bytes, $row.blockedHint)
}

$JD = 'https://item.jd.com/100012043978.html'
$JD_ENC = [Uri]::EscapeDataString($JD)

# ---- 京东 ----
Probe 'jd-p3-price' 'GET' 'https://p.3.cn/prices/mgets?skuIds=J_100012043978' $null $BROWSER_UA 'https://item.jd.com/' $null
Probe 'jd-item-m-ssr' 'GET' 'https://item.m.jd.com/product/100012043978.html' $null $MOBILE_UA 'https://item.m.jd.com/' $null
Probe 'jd-item-pc' 'GET' $JD $null $BROWSER_UA $null $null
Probe 'jd-item-pc-mobile-ua' 'GET' $JD $null $MOBILE_UA $null $null
Probe 'jd-price-soi-1' 'GET' 'https://item-soi-1.jd.com/prices/mgets?skuIds=J_100012043978' $null $BROWSER_UA 'https://item.jd.com/' $null

# ---- 慢慢买 ----
Probe 'mmb-HistoryLowest-page' 'GET' 'https://tool.manmanbuy.com/HistoryLowest.aspx' $null $BROWSER_UA 'https://www.manmanbuy.com/' $null
Probe 'mmb-mobile-history-ssr' 'GET' ('https://tool.manmanbuy.com/m/history.aspx?type=history_mobile_tool&url=' + $JD_ENC) $null $BROWSER_UA 'https://tool.manmanbuy.com/HistoryLowest.aspx' $null
Probe 'mmb-apapia-getHistoryTrend' 'POST' 'https://apapia-history.manmanbuy.com/HistoryLowest.ashx' ('{"methodName":"getHistoryTrend","p_url":"' + $JD + '"}') $BROWSER_UA $null $null
Probe 'mmb-checkJdAuth-noCookie' 'GET' 'https://tool.manmanbuy.com/HistoryLowest.aspx?action=checkJdAuth' $null $BROWSER_UA 'https://tool.manmanbuy.com/HistoryLowest.aspx' $null
Probe 'mmb-aliVal' 'GET' 'https://tool.manmanbuy.com/m/aliVal.aspx?comeFrom=history_mobile_tool' $null $BROWSER_UA $null $null

# ---- 什么值得买（Googlebot UA vs 浏览器 UA）----
Probe 'smzdm-search-faxian-googlebot' 'GET' ('https://search.smzdm.com/?c=faxian&s=' + [Uri]::EscapeDataString('iPhone 15') + '&v=a&order=score') $null $BOT_UA 'https://www.smzdm.com/' $null
Probe 'smzdm-search-faxian-browserua' 'GET' ('https://search.smzdm.com/?c=faxian&s=' + [Uri]::EscapeDataString('iPhone 15') + '&v=a&order=score') $null $BROWSER_UA 'https://www.smzdm.com/' $null
Probe 'smzdm-search-youhui-googlebot' 'GET' ('https://search.smzdm.com/?c=youhui&s=' + [Uri]::EscapeDataString('耳机') + '&v=a&order=score') $null $BOT_UA 'https://www.smzdm.com/' $null

# ---- 当当（GBK 关键词）----
Probe 'dangdang-search-gbk' 'GET' ('https://search.dangdang.com/?key=' + (GBKEnc '手机') + '&act=input') $null $BROWSER_UA 'https://www.dangdang.com/' $null
Probe 'dangdang-search-utf8' 'GET' ('https://search.dangdang.com/?key=' + [Uri]::EscapeDataString('手机') + '&act=input') $null $BROWSER_UA 'https://www.dangdang.com/' $null

# ---- 购物党 ----
Probe 'gwdang-home' 'GET' 'https://www.gwdang.com/' $null $BROWSER_UA $null $null
Probe 'gwdang-search' 'GET' ('https://www.gwdang.com/search?w=' + [Uri]::EscapeDataString('iPhone 15')) $null $BROWSER_UA 'https://www.gwdang.com/' $null
Probe 'gwdang-tuan-search' 'GET' ('https://www.gwdang.com/tuan/search?w=' + [Uri]::EscapeDataString('iPhone 15')) $null $BROWSER_UA 'https://www.gwdang.com/' $null

# ---- 识货 ----
Probe 'shihuo-m-search-api' 'GET' ('https://m.shihuo.cn/search?page=1&page_size=30&type=goods&keywords=' + [Uri]::EscapeDataString('iPhone 15')) $null $MOBILE_UA ('https://m.shihuo.cn/search/goods?keywords=' + [Uri]::EscapeDataString('iPhone 15')) $null
Probe 'shihuo-m-search-page' 'GET' ('https://m.shihuo.cn/search/goods?keywords=' + [Uri]::EscapeDataString('iPhone 15')) $null $MOBILE_UA $null $null

# ---- 星罗 ----
Probe 'linkstars-nokey' 'GET' 'https://openapi.linkstars.com/api/jd_historyLowPriceRank?apikey=test&v=1.0.0&page=1' $null $BROWSER_UA $null $null

# ---- B站 ----
Probe 'bili-nav' 'GET' 'https://api.bilibili.com/x/web-interface/nav' $null $BROWSER_UA 'https://www.bilibili.com' 'buvid3=abc123; b_nut=1762560000'
Probe 'bili-search-unsigned' 'GET' ('https://api.bilibili.com/x/web-interface/wbi/search/type?search_type=video&keyword=' + [Uri]::EscapeDataString('iPhone 15') + '&page=1&platform=pc') $null $BROWSER_UA 'https://www.bilibili.com' 'buvid3=abc123; b_nut=1762560000'

$json = $results | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText((Join-Path $OUT_DIR 'probe-results.json'), $json, (New-Object Text.UTF8Encoding($false)))
Write-Output 'DONE'
