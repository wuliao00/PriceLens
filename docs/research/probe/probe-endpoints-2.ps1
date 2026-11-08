# PriceLens 信息源端点实测 第二轮（修正编码 + DNS/官方通道）——只读
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ErrorActionPreference = 'Continue'
$OUT_DIR = $PSScriptRoot   # 避免在 Windows PowerShell 5.1 下因脚本无 BOM 导致中文路径乱码
$results = New-Object System.Collections.ArrayList
$MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; PLB110) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.0.0 Mobile Safari/537.36'
$BROWSER_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36'
$BOT_UA = 'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)'

function Probe([string]$name, [string]$method, [string]$url, $body, [string]$ua, [string]$referer) {
  $row = [ordered]@{ name = $name; method = $method; url = $url; status = ''; ms = 0; bytes = 0; finalUrl = ''; hint = ''; snippet = ''; note = '' }
  $sw = [Diagnostics.Stopwatch]::StartNew()
  try {
    $params = @{ Uri = $url; Method = $method; TimeoutSec = 8; UseBasicParsing = $true; MaximumRedirection = 3; ErrorAction = 'Stop' }
    if ($ua) { $params['UserAgent'] = $ua }
    if ($referer) { $params['Headers'] = @{ Referer = $referer } }
    if ($body) { $params['Body'] = $body; $params['ContentType'] = 'application/json; charset=utf-8' }
    $r = Invoke-WebRequest @params
    $sw.Stop(); $row.ms = [int]$sw.ElapsedMilliseconds; $row.status = [int]$r.StatusCode
    try { $row.finalUrl = [string]$r.BaseResponse.ResponseUri.AbsoluteUri } catch { }
    $c = $r.Content
    if ($c -is [byte[]]) { $c = [Text.Encoding]::UTF8.GetString($c) }
    $text = [regex]::Replace([string]$c, '\s+', ' ')
    $row.bytes = $text.Length
    $row.snippet = $text.Substring(0, [Math]::Min(300, $text.Length))
    if ($text -match 'search_nature_rg|ul class="bigimg"') { $row.hint += 'HAS_LIST ' }
    if ($text -match 'search_now_price|search_pre_price') { $row.hint += 'HAS_PRICE_NODE ' }
    if ($text -match 'search_nature_rg' -and $text -notmatch 'search_now_price|price_n') { $row.hint += 'LIST_NO_PRICE ' }
    if ($text -match 'captcha|slider|verify|JDV|risk|denied|forbidden') { $row.hint += 'SUSPECT_BLOCK ' }
  } catch {
    $sw.Stop(); $row.ms = [int]$sw.ElapsedMilliseconds
    $resp = $_.Exception.Response
    if ($resp -ne $null) {
      try { $row.status = [int]$resp.StatusCode } catch { $row.status = 'ERR' }
      try { $row.finalUrl = [string]$resp.ResponseUri.AbsoluteUri } catch { }
      try { $sr = New-Object IO.StreamReader($resp.GetResponseStream()); $t = $sr.ReadToEnd(); $row.snippet = [regex]::Replace($t, '\s+', ' '); if ($row.snippet.Length -gt 300) { $row.snippet = $row.snippet.Substring(0, 300) } } catch { }
    } else { $row.status = 'ERR'; $row.note = $_.Exception.Message }
  }
  [void]$results.Add([pscustomobject]$row)
  Write-Output ("[{0}] status={1} {2}ms {3}B {4} {5}" -f $row.name, $row.status, $row.ms, $row.bytes, $row.hint, $row.note)
}

# 当当：GBK 正确编码（手机 = %CA%D6%BB%FA）vs UTF-8（%E6%89%8B%E6%9C%BA）
Probe 'dangdang-gbk-correct' 'GET' 'https://search.dangdang.com/?key=%CA%D6%BB%FA&act=input' $null $BROWSER_UA 'https://www.dangdang.com/'
Probe 'dangdang-utf8-correct' 'GET' 'https://search.dangdang.com/?key=%E6%89%8B%E6%9C%BA&act=input' $null $BROWSER_UA 'https://www.dangdang.com/'
# 什么值得买：耳机（UTF-8 %E8%80%B3%E6%9C%BA）
Probe 'smzdm-youhui-earphone' 'GET' 'https://search.smzdm.com/?c=youhui&s=%E8%80%B3%E6%9C%BA&v=a&order=score' $null $BOT_UA 'https://www.smzdm.com/'
# 京东重测（含 DNS 解析）
Probe 'jd-p3-retry' 'GET' 'https://p.3.cn/prices/mgets?skuIds=J_100012043978' $null $BROWSER_UA 'https://item.jd.com/'
Probe 'jd-search-page' 'GET' 'https://search.jd.com/Search?keyword=%E6%89%8B%E6%9C%BA&enc=utf-8' $null $BROWSER_UA $null
# 官方通道可达性（门槛证据：可访问性 ≠ 有权限）
Probe 'jd-union-open' 'GET' 'https://union.jd.com/' $null $BROWSER_UA $null
Probe 'jd-open' 'GET' 'https://open.jd.com/' $null $BROWSER_UA $null
Probe 'taobao-alimama-open' 'GET' 'https://open.taobao.com/' $null $BROWSER_UA $null
Probe 'alimama-union' 'GET' 'https://union.taobao.com/' $null $BROWSER_UA $null
Probe 'vip-price-protection' 'GET' 'https://mst.vip.com/9_23qAPyWSpMHIO6oy_V1A.php' $null $BROWSER_UA $null
Probe 'tmall-price-protection' 'GET' 'https://pages.tmall.com/wow/act/22351/priceprotection' $null $BROWSER_UA $null
Probe 'pdd-price-protection' 'GET' 'https://mobile.yangkeduo.com/' $null $MOBILE_UA $null
# 识货 4B 响应重测（可能空体）
Probe 'shihuo-api-retry' 'GET' 'https://m.shihuo.cn/search?page=1&page_size=30&type=goods&keywords=iPhone' $null $MOBILE_UA 'https://m.shihuo.cn/search/goods?keywords=iPhone'
# 慢慢买移动历史页（含工具页）
Probe 'mmb-mobile-history-2' 'GET' 'https://tool.manmanbuy.com/m/history.aspx?type=history_mobile_tool&url=https%3A%2F%2Fitem.jd.com%2F100012043978.html' $null $BROWSER_UA 'https://tool.manmanbuy.com/HistoryLowest.aspx'
# 星罗（有/无 apikey 对比）
Probe 'linkstars-badkey' 'GET' 'https://openapi.linkstars.com/api/jd_historyLowPriceRank?apikey=invalid_key_test&v=1.0.0&page=1' $null $BROWSER_UA $null

$json = $results | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText((Join-Path $OUT_DIR 'probe-results-2.json'), $json, (New-Object Text.UTF8Encoding($false)))

# DNS 解析记录（只读）
$dnsOut = @()
foreach ($h in @('p.3.cn', 'item.jd.com', 'item.m.jd.com', 'tool.manmanbuy.com', 'search.smzdm.com')) {
  $line = "== $h ==`n"
  try { $line += (Resolve-DnsName -Name $h -Type A -ErrorAction Stop | Where-Object { $_.IPAddress } | ForEach-Object { $_.IPAddress }) -join ', ' } catch { $line += 'DNS_ERR: ' + $_.Exception.Message }
  $dnsOut += $line
}
[IO.File]::WriteAllText((Join-Path $OUT_DIR 'dns-results.txt'), ($dnsOut -join "`r`n"), (New-Object Text.UTF8Encoding($false)))
Write-Output 'DONE2'
