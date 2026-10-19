// Manual device QA helper. Keeps the original Mac clipboard in memory; never prints its contents.
import AppKit
import CryptoKit
import Foundation
let board = NSPasteboard.general
let original: [[NSPasteboard.PasteboardType: Data]] = (board.pasteboardItems ?? []).map { item in
    Dictionary(uniqueKeysWithValues: item.types.compactMap { type in item.data(forType: type).map { (type, $0) } })
}
var ownedCounts = Set<Int>()
while let line = readLine() {
    do {
        let message = try JSONSerialization.jsonObject(with: Data(line.utf8)) as! [String: String]
        switch message["action"] {
        case "file":
            let url = URL(fileURLWithPath: message["path"]!)
            board.clearContents(); board.writeObjects([url as NSURL]); ownedCounts.insert(board.changeCount)
            print("SET_FILE")
        case "image":
            let bytes = try Data(contentsOf: URL(fileURLWithPath: message["path"]!))
            board.clearContents(); board.setData(bytes, forType: .png); ownedCounts.insert(board.changeCount)
            print("SET_IMAGE")
        case "text":
            board.clearContents(); board.setString(message["text"]!, forType: .string); ownedCounts.insert(board.changeCount)
            print("SET_TEXT")
        case "verify_text":
            let matches = board.string(forType: .string) == message["text"]
            if matches { ownedCounts.insert(board.changeCount) }; print("VERIFY_TEXT \(matches)")
        case "verify_file":
            let urls = board.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
            var matches = false
            if let url = urls.first, url.path.contains("/CopyMore/received/") {
                let bytes = try Data(contentsOf: url)
                matches = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() == message["sha256"]
            }
            if matches { ownedCounts.insert(board.changeCount) }; print("VERIFY_FILE \(matches)")
        case "restore":
            if ownedCounts.contains(board.changeCount) {
                board.clearContents()
                let items = original.map { values -> NSPasteboardItem in
                    let item = NSPasteboardItem(); for (type, data) in values { item.setData(data, forType: type) }; return item
                }
                board.writeObjects(items); print("RESTORED")
            } else { print("RESTORE_SKIPPED_NEW_USER_COPY") }
            fflush(stdout); exit(0)
        default: print("UNKNOWN")
        }
    } catch { print("TEST_ERROR \(error.localizedDescription)") }
    fflush(stdout)
}
