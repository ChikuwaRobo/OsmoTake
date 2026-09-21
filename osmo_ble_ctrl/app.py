from __future__ import annotations

import asyncio
import concurrent.futures
import queue
import threading
import tkinter as tk
from datetime import datetime
from tkinter import filedialog, messagebox, ttk

from .client import DeviceInfo, NanoClient, PAIRING_TOKEN
from .settings import persistent_identifier


class AsyncWorker:
    def __init__(self) -> None:
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self._run, name="BLE event loop", daemon=True)
        self.thread.start()

    def _run(self) -> None:
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()

    def submit(self, coroutine: object) -> concurrent.futures.Future[object]:
        return asyncio.run_coroutine_threadsafe(coroutine, self.loop)  # type: ignore[arg-type]

    def stop(self) -> None:
        async def cleanup() -> None:
            current = asyncio.current_task()
            tasks = [task for task in asyncio.all_tasks() if task is not current]
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

        try:
            asyncio.run_coroutine_threadsafe(cleanup(), self.loop).result(timeout=3)
        except Exception:
            pass
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.join(timeout=2)


class App(ttk.Frame):
    def __init__(self, root: tk.Tk) -> None:
        super().__init__(root, padding=14)
        self.root = root
        self.events: queue.Queue[tuple[str, object]] = queue.Queue()
        self.worker = AsyncWorker()
        self.client = NanoClient(persistent_identifier(), self._post)
        self.devices: list[DeviceInfo] = []
        self.connected = False
        self.ready = False
        self.pending: str | None = None
        self.last_recording = "不明"
        self.scanning = False
        self.connecting = False
        self._closing = False

        root.title("Osmo Nano BLE Ctrl")
        root.minsize(610, 455)
        root.protocol("WM_DELETE_WINDOW", self.close)
        self.pack(fill="both", expand=True)
        self._build()
        self._refresh_buttons()
        self.after(80, self._poll_events)

    def _build(self) -> None:
        top = ttk.Frame(self)
        top.pack(fill="x")
        self.scan_button = ttk.Button(top, text="Nanoを検索", command=self.scan)
        self.scan_button.pack(side="left")
        self.device_box = ttk.Combobox(top, state="readonly", width=45)
        self.device_box.pack(side="left", fill="x", expand=True, padx=8)
        self.connect_button = ttk.Button(top, text="接続", command=self.connect)
        self.connect_button.pack(side="left")
        self.disconnect_button = ttk.Button(top, text="切断", command=self.disconnect)
        self.disconnect_button.pack(side="left", padx=(8, 0))

        state = ttk.LabelFrame(self, text="状態", padding=12)
        state.pack(fill="x", pady=12)
        self.status_var = tk.StringVar(value="Nanoの電源を入れて「Nanoを検索」を押してください")
        self.record_var = tk.StringVar(value="録画状態: 不明")
        ttk.Label(state, textvariable=self.status_var, wraplength=565).pack(anchor="w")
        ttk.Label(state, textvariable=self.record_var, font=("Yu Gothic UI", 13, "bold")).pack(anchor="w", pady=(8, 0))
        ttk.Label(state, text=f"初回承認時にNano画面へ表示される名前: {PAIRING_TOKEN}").pack(anchor="w", pady=(6, 0))

        actions = ttk.Frame(self)
        actions.pack(fill="x")
        self.start_button = ttk.Button(actions, text="● 録画開始", command=lambda: self.record(True))
        self.start_button.pack(side="left", fill="x", expand=True, padx=(0, 5), ipady=8)
        self.stop_button = ttk.Button(actions, text="■ 録画停止", command=lambda: self.record(False))
        self.stop_button.pack(side="left", fill="x", expand=True, padx=(5, 0), ipady=8)

        log_frame = ttk.LabelFrame(self, text="通信ログ", padding=6)
        log_frame.pack(fill="both", expand=True, pady=(12, 0))
        self.log = tk.Text(log_frame, height=10, state="disabled", wrap="none", font=("Consolas", 9))
        scroll = ttk.Scrollbar(log_frame, orient="vertical", command=self.log.yview)
        self.log.configure(yscrollcommand=scroll.set)
        self.log.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        ttk.Button(self, text="ログを保存…", command=self.save_log).pack(anchor="e", pady=(5, 0))

    def _post(self, kind: str, value: object) -> None:
        self.events.put((kind, value))

    def _submit(self, coroutine: object) -> None:
        future = self.worker.submit(coroutine)

        def done(result: concurrent.futures.Future[object]) -> None:
            try:
                result.result()
            except (asyncio.CancelledError, concurrent.futures.CancelledError):
                return
            except Exception as exc:
                self._post("error", str(exc))

        future.add_done_callback(done)

    def scan(self) -> None:
        self.scanning = True
        self._refresh_buttons()
        self._submit(self.client.scan())

    def connect(self) -> None:
        index = self.device_box.current()
        if index < 0 or index >= len(self.devices):
            messagebox.showinfo("Osmo Nano", "接続するNanoを選択してください")
            return
        self.connecting = True
        self.status_var.set("接続中…")
        self._refresh_buttons()
        self._submit(self.client.connect(self.devices[index].address))

    def disconnect(self) -> None:
        self.connecting = False
        self._submit(self.client.disconnect())

    def record(self, start: bool) -> None:
        self._submit(self.client.record(start))

    def _poll_events(self) -> None:
        try:
            while True:
                kind, value = self.events.get_nowait()
                self._handle_event(kind, value)
        except queue.Empty:
            pass
        if not self._closing:
            self.after(80, self._poll_events)

    def _handle_event(self, kind: str, value: object) -> None:
        if kind == "devices":
            self.scanning = False
            self.devices = list(value)  # type: ignore[arg-type]
            self.device_box["values"] = [
                f"{item.name}  ({item.rssi if item.rssi is not None else '?'} dBm)" for item in self.devices
            ]
            if self.devices:
                self.device_box.current(0)
            self.scan_button.configure(state="normal")
        elif kind == "connected":
            self.connected = bool(value)
            if self.connected:
                self.connecting = False
            if not self.connected:
                self.ready = False
                self.pending = None
        elif kind == "connecting":
            self.connecting = bool(value)
        elif kind == "pairing":
            self.ready = value == "ready"
        elif kind == "pending":
            self.pending = value if isinstance(value, str) else None
            if value == "start":
                self.record_var.set("録画状態: 開始コマンド送信済み（カメラ確認待ち）")
            elif value == "stop":
                self.record_var.set("録画状態: 停止コマンド送信済み（カメラ確認待ち）")
        elif kind == "recording":
            labels = {"recording": "録画中（カメラ確認済み）", "stopped": "停止中（カメラ確認済み）", "unknown": "不明"}
            if not self.pending or value == ("recording" if self.pending == "start" else "stopped"):
                self.last_recording = labels.get(str(value), str(value))
                self.record_var.set(f"録画状態: {self.last_recording}")
        elif kind == "confirmation_timeout":
            self.record_var.set(f"録画状態: 未確認（最後の確認: {self.last_recording}）")
        elif kind == "status":
            self.status_var.set(str(value))
        elif kind == "log":
            self._append_log(str(value))
        elif kind == "error":
            self.scanning = False
            self.connecting = False
            self.status_var.set(f"エラー: {value}")
            self._append_log(f"ERROR {value}")
            self.scan_button.configure(state="normal")
        self._refresh_buttons()

    def _append_log(self, line: str) -> None:
        self.log.configure(state="normal")
        self.log.insert("end", f"{datetime.now():%H:%M:%S} {line}\n")
        line_count = int(self.log.index("end-1c").split(".")[0])
        if line_count > 1000:
            self.log.delete("1.0", f"{line_count - 999}.0")
        self.log.see("end")
        self.log.configure(state="disabled")

    def _refresh_buttons(self) -> None:
        self.scan_button.configure(state="disabled" if self.scanning or self.connecting or self.connected else "normal")
        self.connect_button.configure(state="normal" if self.devices and not (self.scanning or self.connecting or self.connected) else "disabled")
        self.disconnect_button.configure(state="normal" if self.connected or self.connecting else "disabled")
        base = self.connected and self.ready
        self.start_button.configure(state="normal" if base and not self.pending else "disabled")
        self.stop_button.configure(state="normal" if base and self.pending != "stop" else "disabled")

    def save_log(self) -> None:
        path = filedialog.asksaveasfilename(
            title="通信ログを保存", defaultextension=".txt",
            filetypes=[("Text", "*.txt"), ("All files", "*.*")],
        )
        if path:
            with open(path, "w", encoding="utf-8") as output:
                output.write(self.log.get("1.0", "end-1c"))

    def close(self) -> None:
        self._closing = True
        try:
            self.worker.submit(self.client.disconnect()).result(timeout=3)
        except Exception:
            pass
        self.worker.stop()
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    try:
        root.call("tk", "scaling", 1.15)
    except tk.TclError:
        pass
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
