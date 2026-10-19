import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ArrowLeftIcon, CheckIcon, ChevronRightIcon, ClipboardCopyIcon, Cross2Icon, DownloadIcon, FileTextIcon, GearIcon, ImageIcon, LaptopIcon, MobileIcon, TextIcon } from '@radix-ui/react-icons';
import { BottomSheet, KeyboardInput, MobileScroll, useKeyboard, useScreenPortal } from './mobile';

type Kind = 'text' | 'image' | 'file';
type Item = { id: string; kind: Kind; title: string; time: string; detail: string };
const samples: Record<Kind, Item> = {
  text: { id: 'text', kind: 'text', title: '会议改到下午三点，记得带上方案。', time: '09:41', detail: '文字' },
  image: { id: 'image', kind: 'image', title: 'IMG_9234.PNG', time: '09:38', detail: 'PNG 图片 · 1536 × 1024' },
  file: { id: 'file', kind: 'file', title: '产品需求文档.txt', time: '09:21', detail: '文本文档 · 1 KB' },
};
const PAIR_CODE = '628419';
function ItemIcon({ kind }: { kind: Kind }) { return kind === 'image' ? <ImageIcon /> : kind === 'file' ? <FileTextIcon /> : <TextIcon />; }
function Switch({ checked, onChange, label }: { checked: boolean; onChange: () => void; label: string }) {
  return <button className="cm-switch" role="switch" aria-label={label} aria-checked={checked} onClick={onChange}><span /></button>;
}
export default function Prototype() {
  const [connected, setConnected] = useState(true);
  const [paired, setPaired] = useState(true);
  const [wifiAvailable, setWifiAvailable] = useState(true);
  const [reconnecting, setReconnecting] = useState(false);
  const [autoSync, setAutoSync] = useState(true);
  const [pcItem, setPcItem] = useState<Item>(samples.text);
  const [received, setReceived] = useState<Item>(samples.text);
  const [history, setHistory] = useState<Item[]>([samples.text, samples.image, samples.file]);
  const [transfer, setTransfer] = useState(false);
  const [sheet, setSheet] = useState<'history' | 'device' | null>(null);
  const [pcView, setPcView] = useState<'main' | 'settings' | 'pair'>('main');
  const [platform, setPlatform] = useState<'Mac' | 'Windows'>('Mac');
  const [code, setCode] = useState('');
  const [pairError, setPairError] = useState('');
  const [pairing, setPairing] = useState(false);
  const [copied, setCopied] = useState(false);
  const [toast, setToast] = useState('');
  const [boot, setBoot] = useState(true);
  const [connectionTip, setConnectionTip] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const connectionTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const latestItem = useRef(pcItem);
  const syncEnabled = useRef(autoSync);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const keyboard = useKeyboard();
  const { screenRef } = useScreenPortal();
  // Native focus can scroll the overflow-hidden preview screen. Keep that outer
  // viewport fixed; MobileScroll continues to own the app's actual scrolling.
  useEffect(() => {
    const frame = requestAnimationFrame(() => screenRef.current?.scrollTo(0, 0));
    return () => cancelAnimationFrame(frame);
  }, [connected, sheet, keyboard.visible, screenRef]);
  const computerName = platform === 'Mac' ? 'MacBook Pro' : '我的 Windows 电脑';
  const notify = (message: string) => {
    setToast(message);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(''), 3200);
  };
  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
    if (connectionTimer.current) clearTimeout(connectionTimer.current);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    if (copyTimer.current) clearTimeout(copyTimer.current);
  }, []);
  function cancelTransfer() { if (timer.current) clearTimeout(timer.current); setTransfer(false); }
  function deliver(item: Item) {
    cancelTransfer(); setTransfer(true);
    timer.current = setTimeout(() => {
      setReceived(item); setHistory(prev => [item, ...prev.filter(entry => entry.kind !== item.kind)]);
      setCopied(false); setTransfer(false);
    }, 850);
  }
  function simulate(kind: Kind) {
    const item = { ...samples[kind], id: `${kind}-${Date.now()}`, time: '刚刚' };
    latestItem.current = item; setPcItem(item); if (connected && autoSync) deliver(item);
  }
  function toggleSync() {
    if (autoSync) cancelTransfer(); else if (connected && received.id !== pcItem.id) deliver(pcItem);
    syncEnabled.current = !autoSync; setAutoSync(!autoSync);
  }
  function disconnect() {
    if (connectionTimer.current) clearTimeout(connectionTimer.current);
    keyboard.hide(); cancelTransfer(); setConnected(false); setPaired(false); setReconnecting(false); setWifiAvailable(true); setPairing(false); setSheet(null); setPcView('pair'); setCode(''); setPairError('');
  }
  function pair() {
    keyboard.hide();
    if (code !== PAIR_CODE) { setPairError('配对码不正确，请查看电脑上的 6 位数字。'); return; }
    setPairing(true);
    if (connectionTimer.current) clearTimeout(connectionTimer.current);
    connectionTimer.current = setTimeout(() => {
      setPairing(false); setPaired(true); setConnected(true); setPcView('main'); setPairError('');
      if (syncEnabled.current) deliver(latestItem.current); notify('配对已记住，下次自动连接');
    }, 650);
  }
  function toggleWifi() {
    if (connectionTimer.current) clearTimeout(connectionTimer.current);
    keyboard.hide(); cancelTransfer(); setSheet(null);
    if (wifiAvailable) {
      setWifiAvailable(false); setConnected(false); setReconnecting(false);
    } else {
      setWifiAvailable(true);
      if (!paired) return;
      setReconnecting(true);
      connectionTimer.current = setTimeout(() => {
        setReconnecting(false); setConnected(true);
        if (syncEnabled.current) deliver(latestItem.current);
        notify('已自动连接，无需重新配对');
      }, 950);
    }
  }
  async function copyCurrent() {
    try {
      if (received.kind === 'image') {
        if (!navigator.clipboard?.write || !window.ClipboardItem) { notify('当前浏览器不支持复制图片，请保存后使用'); return; }
        const blobPromise = fetch('/images/alpine-lake.png').then(r => { if (!r.ok) throw new Error('image'); return r.blob(); });
        await navigator.clipboard.write([new ClipboardItem({ 'image/png': blobPromise })]);
      } else { await navigator.clipboard.writeText(received.title); }
      setCopied(true); notify(received.kind === 'image' ? '图片已复制，可以粘贴了' : '已复制，可以去粘贴了');
      if (copyTimer.current) clearTimeout(copyTimer.current);
      copyTimer.current = setTimeout(() => setCopied(false), 2500);
    } catch { notify('复制未成功，请允许剪贴板访问后重试'); }
  }
  function download() {
    const a = document.createElement('a');
    a.href = received.kind === 'image' ? '/images/alpine-lake.png' : '/samples/product-brief.txt';
    a.download = received.title; a.click(); notify('已开始下载，请在下载列表查看');
  }
  function selectItem(item: Item) { keyboard.hide(); setReceived(item); setCopied(false); setSheet(null); }
  const historyRows = (items: Item[]) => items.map(item => <button className="cm-history-row" key={item.id} onClick={() => selectItem(item)}>
    <span className="cm-type-icon"><ItemIcon kind={item.kind} /></span>
    <span className="cm-history-copy"><strong>{item.title}</strong><small>来自 {platform} · {item.time}</small></span><ChevronRightIcon className="cm-row-arrow" />
  </button>);
  const desktop = <aside className="cm-desktop-preview" aria-label="电脑端预览">
    <div className="cm-preview-heading"><ClipboardCopyIcon /><b>CopyMore</b><span>交互原型</span></div>
    <div className="cm-desktop-scene" data-platform={platform}>
      <img className="cm-wallpaper" src="/images/alpine-lake.png" alt="" />
      <div className="cm-system-strip"><span>{platform === 'Mac' ? 'MacBook Pro' : 'Windows'}</span><div><ClipboardCopyIcon /><span>10月19日 周一</span><b>09:41</b></div></div>
      <section className="cm-tray" aria-label="CopyMore 电脑面板">
        <header className="cm-tray-heading">{pcView !== 'main' && <button className="cm-icon-button" aria-label="返回电脑首页" onClick={() => setPcView('main')}><ArrowLeftIcon /></button>}<h1>{pcView === 'settings' ? '设置' : pcView === 'pair' ? '连接手机' : 'CopyMore'}</h1>{pcView === 'main' && <button className="cm-icon-button" aria-label="电脑设置" onClick={() => setPcView('settings')}><GearIcon /></button>}</header>
        {pcView === 'main' ? <>
          <button className="cm-connected-device" onClick={() => setConnectionTip(!connectionTip)} aria-expanded={connectionTip}><span className={`cm-dot ${connected ? '' : 'offline'}`} /><span><strong>{paired ? '李的手机' : '还没有连接手机'}</strong><small>{connected ? '已连接 · 同一 Wi-Fi' : paired ? (reconnecting ? '正在自动连接…' : '已配对 · 等待手机上线') : '连接后即可共享剪贴板'}</small></span><ChevronRightIcon /></button>
          {connectionTip && <div className="cm-device-detail">{connected ? <>已记住此设备<button onClick={disconnect}>取消配对</button></> : <button onClick={() => setPcView('pair')}>开始连接</button>}</div>}
          <div className="cm-sync-row"><div><strong>自动同步</strong><p>复制内容后自动发送到已连接的手机</p></div><Switch checked={autoSync} onChange={toggleSync} label="自动同步" /></div>
          <div className="cm-latest"><span className="cm-caption">最新内容</span><div className="cm-tray-content">{pcItem.kind === 'text' ? <p>{pcItem.title}</p> : <div className="cm-tray-attachment">{pcItem.kind === 'image' ? <img src="/images/alpine-lake.png" alt="山间湖泊" /> : <span className="cm-type-icon"><FileTextIcon /></span>}<span><strong>{pcItem.title}</strong><small>{pcItem.detail}</small></span></div>}<footer><span>本机 · 刚刚</span><span className={transfer ? 'cm-transferring' : ''}>{!connected ? (paired ? '等待自动连接' : '等待配对') : !autoSync ? '已暂停' : transfer ? '发送中…' : '已发送'}{connected && autoSync && !transfer && <CheckIcon />}</span></footer></div></div>
          <button className="cm-tray-footer" onClick={() => setPcView('pair')}>连接新设备</button>
        </> : pcView === 'settings' ? <div className="cm-settings">
          <div className="cm-setting-row"><div><strong>开机启动</strong><p>打开电脑，CopyMore 就准备好了</p></div><Switch checked={boot} onChange={() => setBoot(!boot)} label="开机启动" /></div>
          <div className="cm-setting-row"><div><strong>自动同步</strong><p>暂停后，新复制的内容留在本机</p></div><Switch checked={autoSync} onChange={toggleSync} label="自动同步" /></div>
          <div className="cm-setting-note"><span>CopyMore</span><small>设计预览 · 设置仅在本次体验中生效</small></div>
        </div> : <div className="cm-pair-desktop"><span className="cm-pair-symbol"><MobileIcon /></span><h2>让电脑和手机连起来</h2><p>两台设备连接同一 Wi-Fi 后，在手机输入</p><div className="cm-pair-code" aria-label={`配对码 ${PAIR_CODE}`}>{PAIR_CODE.slice(0,3)} {PAIR_CODE.slice(3)}</div><span className="cm-caption">首次配对一次，之后自动连接</span>{connected ? <button className="cm-secondary" onClick={disconnect}>体验手机配对</button> : <div className="cm-waiting"><span className="cm-dot" />等待手机连接</div>}</div>}
      </section>
    </div>
    <div className="cm-demo-bar"><div><span className="cm-demo-label">模拟电脑复制</span><div className="cm-demo-actions">{(['text','image','file'] as Kind[]).map(kind => <button key={kind} aria-pressed={pcItem.kind === kind} onClick={() => simulate(kind)}><ItemIcon kind={kind} />{{text:'文字',image:'图片',file:'文件'}[kind]}</button>)}</div></div><div className="cm-platform-picker"><button aria-pressed={platform === 'Mac'} onClick={() => setPlatform('Mac')}>Mac</button><button aria-pressed={platform === 'Windows'} onClick={() => setPlatform('Windows')}>Windows</button></div></div>
    <div className="cm-demo-footnote"><span>原生客户端 UI 预览 · 非正式应用</span><div className="cm-demo-network"><button onClick={toggleWifi} disabled={reconnecting}>{wifiAvailable ? '模拟离开 Wi-Fi' : '回到同一 Wi-Fi'}</button><button onClick={disconnect}>首次配对 <ChevronRightIcon /></button></div></div>
  </aside>;
  return <>
    {createPortal(desktop, document.body)}
    <MobileScroll className="app-screen"><main className="cm-mobile-content">
      <header className="cm-mobile-heading"><h1>CopyMore</h1><button className="cm-mobile-connection" onClick={() => paired ? setSheet('device') : setPcView('pair')}><span className={`cm-dot ${connected ? '' : 'offline'}`} />{connected ? `${computerName} 已连接` : paired ? (reconnecting ? '正在自动连接…' : `${computerName} 暂时离线`) : '等待首次配对'}</button></header>
      {paired ? <>
        {!connected && <div className="cm-offline-note">{reconnecting ? '已发现配对的电脑，正在连接。' : '配对已保留。回到同一 Wi-Fi 后自动连接。'}</div>}
        <section className="cm-received" aria-busy={transfer}><h2>{received.kind === 'text' ? '电脑刚刚复制的内容' : received.kind === 'image' ? '电脑刚刚复制的图片' : '电脑刚刚复制的文件'}</h2><p className="cm-receive-meta">{transfer ? '正在接收新的内容…' : '已接收'}{!transfer && ` · ${received.time === '刚刚' ? '刚刚' : `10月19日 ${received.time}`}`}</p>
          <div className={`cm-content-preview ${received.kind}`}>{received.kind === 'text' ? <p>{received.title}</p> : received.kind === 'image' ? <><img src="/images/alpine-lake.png" alt="蓝灰色山峦与平静的湖泊" /><div className="cm-attachment-caption"><strong>{received.title}</strong><span>{received.detail}</span></div></> : <div className="cm-file-preview"><FileTextIcon /><strong>{received.title}</strong><span>{received.detail}</span><p>文件已接收，保存后即可打开使用。</p></div>}</div>
          <button className={`cm-primary ${copied ? 'success' : ''}`} onClick={received.kind === 'file' ? download : copyCurrent}>{copied ? <><CheckIcon />已复制</> : received.kind === 'file' ? <><DownloadIcon />保存文件</> : received.kind === 'image' ? '复制图片' : '复制文字'}</button>
          {received.kind === 'image' && <button className="cm-save-image" onClick={download}>保存图片</button>}<button className="cm-history-link" onClick={() => setSheet('history')}>历史记录</button>
        </section>
        <section className="cm-recent"><h2>最近</h2>{historyRows(history.filter(item => item.kind !== received.kind).slice(0,2))}</section>
      </> : <section className="cm-pair-mobile"><span className="cm-pair-symbol"><LaptopIcon /></span><h2>连接你的电脑</h2><p>电脑和手机连接同一个 Wi-Fi，<br />输入配对码一次，以后自动连接。</p>
        <label htmlFor="pair-code">6 位配对码</label><KeyboardInput id="pair-code" aria-label="6 位配对码" className="cm-code-input" inputMode="numeric" autoComplete="off" maxLength={6} value={code} onChange={e => { setCode(e.target.value.replace(/\D/g,'')); setPairError(''); }} onKeyDown={e => {if(e.key === 'Enter') pair();}} placeholder="输入配对码" />
        {pairError && <p className="cm-form-error" role="alert">{pairError}</p>}<button className="cm-primary" disabled={code.length !== 6 || pairing} onClick={pair}>{pairing ? '正在连接…' : '连接电脑'}</button><p className="cm-pair-help">在电脑托盘中打开 CopyMore，<br />点击「连接新设备」即可找到配对码。</p><div className="cm-mobile-only-demo">原型体验配对码：628 419</div>
      </section>}
    </main></MobileScroll>
    <BottomSheet open={sheet === 'history'} onOpenChange={open => setSheet(open ? 'history' : null)} title="历史记录" description="最近从电脑接收的内容" snap={0.62}><button className="cm-sheet-close" aria-label="关闭历史记录" onClick={() => setSheet(null)}><Cross2Icon /></button><div className="cm-history-list">{historyRows(history)}</div><p className="cm-sheet-note">点击一条记录，即可再次复制或保存。</p></BottomSheet>
    <BottomSheet open={sheet === 'device'} onOpenChange={open => setSheet(open ? 'device' : null)} title="已连接的电脑" description="已记住此设备，同一 Wi-Fi 下自动连接" snap={0.5}><button className="cm-sheet-close" aria-label="关闭设备详情" onClick={() => setSheet(null)}><Cross2Icon /></button><div className="cm-device-sheet"><LaptopIcon /><h3>{computerName}</h3><p><span className={`cm-dot ${connected ? '' : 'offline'}`} />{connected ? (autoSync ? '已连接 · 自动同步中' : '已连接 · 同步已暂停') : '等待上线 · 无需重新配对'}</p><button className="cm-secondary" onClick={() => setSheet(null)}>完成</button><button className="cm-disconnect" onClick={disconnect}>取消配对</button></div></BottomSheet>
    <div className={`cm-toast ${toast ? 'visible' : ''}`} role="status" aria-live="polite">{toast}</div>
  </>;
}
