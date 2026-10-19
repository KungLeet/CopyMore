import XCTest
@testable import CopyMoreCore

final class WireTests: XCTestCase {
    func testFragmentedAndCoalescedFrames() throws {
        var decoder = LineDecoder()
        XCTAssertEqual(try decoder.append(Data("{\"type\":".utf8)).count, 0)
        let lines = try decoder.append(Data("\"ping\"}\n{}\npartial".utf8))
        XCTAssertEqual(lines.map { String(data: $0, encoding: .utf8)! }, ["{\"type\":\"ping\"}", "{}"])
        XCTAssertEqual(try decoder.append(Data("\n".utf8)), [Data("partial".utf8)])
    }
    func testRejectsUnterminatedOversizedFrames() {
        var decoder = LineDecoder()
        XCTAssertThrowsError(try decoder.append(Data(repeating: 65, count: 17), maximum: 16))
    }
    func testConstantTimeComparison() {
        XCTAssertTrue(constantTimeEqual("abc", "abc")); XCTAssertFalse(constantTimeEqual("abc", "abd")); XCTAssertFalse(constantTimeEqual("abc", "ab"))
    }
}
