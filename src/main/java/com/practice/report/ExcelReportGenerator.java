package com.practice.report;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ====================================================================================================
 * Pure Java Excel Report Generator (Apache POI)
 * ====================================================================================================
 * 
 * Replaces Python reporting entirely.
 * Reads the test execution log files:
 *   1. Apply_Leave_Test_Report.log
 *   2. Login_Test_Report.log
 * 
 * Generates professionally styled Excel (.xlsx) reports:
 *   1. Test_Execution_Reports.xlsx (Master Workbook with Executive Dashboard, Suites, Cases, and APIs)
 *   2. Apply_Leave_Test_Report.xlsx (Standalone Suite Workbook)
 *   3. Login_Test_Report.xlsx (Standalone Suite Workbook)
 * 
 * Can be executed standalone via main() or invoked directly at the completion of test execution.
 * ====================================================================================================
 */
public class ExcelReportGenerator {

    public static final String APPLY_LEAVE_LOG = "Apply_Leave_Test_Report.log";
    public static final String LOGIN_LOG = "Login_Test_Report.log";

    public static final String MASTER_EXCEL = "Test_Execution_Reports.xlsx";
    public static final String APPLY_LEAVE_EXCEL = "Apply_Leave_Test_Report.xlsx";
    public static final String LOGIN_EXCEL = "Login_Test_Report.xlsx";

    // Palette Colors
    private static final byte[] COLOR_NAVY_HEADER = new byte[]{(byte) 0x1F, (byte) 0x4E, (byte) 0x79};
    private static final byte[] COLOR_SUB_HEADER = new byte[]{(byte) 0x2F, (byte) 0x55, (byte) 0x97};
    private static final byte[] COLOR_CARD_BG = new byte[]{(byte) 0xE9, (byte) 0xEE, (byte) 0xF4};
    private static final byte[] COLOR_ZEBRA_BG = new byte[]{(byte) 0xF9, (byte) 0xFA, (byte) 0xFB};
    private static final byte[] COLOR_PASS_FILL = new byte[]{(byte) 0xE2, (byte) 0xEF, (byte) 0xDA};
    private static final byte[] COLOR_PASS_TEXT = new byte[]{(byte) 0x27, (byte) 0x6A, (byte) 0x3C};
    private static final byte[] COLOR_FAIL_FILL = new byte[]{(byte) 0xFC, (byte) 0xE4, (byte) 0xD6};
    private static final byte[] COLOR_FAIL_TEXT = new byte[]{(byte) 0xC0, (byte) 0x00, (byte) 0x00};
    private static final byte[] COLOR_BORDER = new byte[]{(byte) 0xD9, (byte) 0xD9, (byte) 0xD9};
    private static final byte[] COLOR_GRAY_TEXT = new byte[]{(byte) 0x59, (byte) 0x59, (byte) 0x59};

    // ------------------------------------------------------------------------------------------------
    // Data Structures
    // ------------------------------------------------------------------------------------------------

    public static class SuiteInfo {
        public String suiteTitle = "";
        public String generatedDatetime = "";
        public String targetUrl = "";
        public String browser = "";
        public String scope = "";
        public int totalScenarios = 0;
        public int passedScenarios = 0;
        public int failedScenarios = 0;
        public String successRate = "";
        public String completedTimestamp = "";
    }

    public static class NetworkRequest {
        public String num = "";
        public String url = "";
        public String method = "";
        public String params = "";
        public String headers = "";
        public String status = "";
        public String mime = "";
        public String body = "";
    }

    public static class TestCaseData {
        public String tcId = "";
        public String tcName = "";
        public String description = "";
        public String timestamp = "";

        // Apply leave
        public String leaveType = "";
        public String fromDate = "";
        public String toDate = "";
        public String dayType = "";
        public String sessionVerified = "";
        public String remarks = "";
        public String fileAttached = "";

        // Login
        public String loginId = "";
        public String password = "";

        public String expectedType = "";
        public String finalPageUrl = "";
        public String pageTitle = "";
        public String uiMessage = "";
        public String status = "PASSED"; // PASSED, FAILED, INFO
        public String verdictDetails = "";
        public List<NetworkRequest> requests = new ArrayList<>();
    }

    public static class SuiteReport {
        public SuiteInfo info = new SuiteInfo();
        public List<TestCaseData> cases = new ArrayList<>();
    }

    // ------------------------------------------------------------------------------------------------
    // Main Method
    // ------------------------------------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("==================================================================================");
        System.out.println("            PURE JAVA TEST REPORT EXCEL EXPORTER (APACHE POI)                    ");
        System.out.println("==================================================================================");
        generateReports();
    }

    /**
     * Reads all existing log files and writes the corresponding Excel workbooks.
     */
    public static void generateReports() {
        File applyLogFile = new File(APPLY_LEAVE_LOG);
        File loginLogFile = new File(LOGIN_LOG);

        SuiteReport applyReport = applyLogFile.exists() ? parseReportFile(applyLogFile) : null;
        SuiteReport loginReport = loginLogFile.exists() ? parseReportFile(loginLogFile) : null;

        if (applyReport == null && loginReport == null) {
            System.out.println("[WARN] No log files found (" + APPLY_LEAVE_LOG + " or " + LOGIN_LOG + "). Nothing to export.");
            return;
        }

        if (applyReport != null) {
            System.out.println("[PARSER] Parsed " + applyReport.cases.size() + " test cases from " + APPLY_LEAVE_LOG);
        }
        if (loginReport != null) {
            System.out.println("[PARSER] Parsed " + loginReport.cases.size() + " test cases from " + LOGIN_LOG);
        }

        // 1. Generate Master Consolidated Workbook
        System.out.println("[EXCEL] Generating Master Workbook: " + MASTER_EXCEL + " ...");
        try (XSSFWorkbook wbMaster = new XSSFWorkbook()) {
            StyleContext ctx = new StyleContext(wbMaster);
            createDashboardSheet(wbMaster, ctx, applyReport, loginReport);
            if (applyReport != null) {
                buildApplyLeaveCasesSheet(wbMaster, ctx, applyReport.cases);
                buildApplyLeaveNetworkSheet(wbMaster, ctx, applyReport.cases);
            }
            if (loginReport != null) {
                buildLoginCasesSheet(wbMaster, ctx, loginReport.cases);
                buildLoginNetworkSheet(wbMaster, ctx, loginReport.cases);
            }
            saveWorkbook(wbMaster, MASTER_EXCEL);
            System.out.println("[SUCCESS] Saved " + MASTER_EXCEL);
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to create " + MASTER_EXCEL + ": " + e.getMessage());
            e.printStackTrace();
        }

        // 2. Generate Standalone Apply Leave Workbook
        if (applyReport != null) {
            System.out.println("[EXCEL] Generating Standalone Workbook: " + APPLY_LEAVE_EXCEL + " ...");
            try (XSSFWorkbook wbApply = new XSSFWorkbook()) {
                StyleContext ctx = new StyleContext(wbApply);
                buildApplyLeaveCasesSheet(wbApply, ctx, applyReport.cases);
                buildApplyLeaveNetworkSheet(wbApply, ctx, applyReport.cases);
                saveWorkbook(wbApply, APPLY_LEAVE_EXCEL);
                System.out.println("[SUCCESS] Saved " + APPLY_LEAVE_EXCEL);
            } catch (Exception e) {
                System.err.println("[ERROR] Failed to create " + APPLY_LEAVE_EXCEL + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        // 3. Generate Standalone Login Workbook
        if (loginReport != null) {
            System.out.println("[EXCEL] Generating Standalone Workbook: " + LOGIN_EXCEL + " ...");
            try (XSSFWorkbook wbLogin = new XSSFWorkbook()) {
                StyleContext ctx = new StyleContext(wbLogin);
                buildLoginCasesSheet(wbLogin, ctx, loginReport.cases);
                buildLoginNetworkSheet(wbLogin, ctx, loginReport.cases);
                saveWorkbook(wbLogin, LOGIN_EXCEL);
                System.out.println("[SUCCESS] Saved " + LOGIN_EXCEL);
            } catch (Exception e) {
                System.err.println("[ERROR] Failed to create " + LOGIN_EXCEL + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static void saveWorkbook(XSSFWorkbook wb, String filename) throws IOException {
        try (FileOutputStream out = new FileOutputStream(filename)) {
            wb.write(out);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Log Parsing
    // ------------------------------------------------------------------------------------------------

    public static SuiteReport parseReportFile(File file) {
        SuiteReport report = new SuiteReport();
        String content;
        try {
            content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to read " + file.getName() + ": " + e.getMessage());
            return report;
        }

        // Normalize line breaks
        content = content.replace("\r\n", "\n").replace("\r", "\n");

        // Suite Info
        report.info.suiteTitle = findMatch(content, "SELENIUM AUTOMATION TEST REPORT:\\s*([^\\n=]+)", file.getName());
        report.info.generatedDatetime = findMatch(content, "Generated Date & Time\\s*:\\s*([^\\n]+)", "");
        report.info.targetUrl = findMatch(content, "Target Website URL\\s*:\\s*([^\\n]+)", "");
        report.info.browser = findMatch(content, "Browser Engine\\s*:\\s*([^\\n]+)", "");
        report.info.scope = findMatch(content, "(?:Scope|Test Scope)\\s*:\\s*(.*?)(?=\\n\\s*(?:Baseline Credentials|={50,}))", Pattern.DOTALL, "");
        report.info.scope = report.info.scope.replaceAll("\\s+", " ").trim();

        String totalStr = findMatch(content, "Total Scenarios Executed\\s*:\\s*(\\d+)", "");
        report.info.totalScenarios = totalStr.isEmpty() ? 0 : Integer.parseInt(totalStr);

        String passStr = findMatch(content, "Passed Scenarios\\s*:\\s*(\\d+)", "");
        report.info.passedScenarios = passStr.isEmpty() ? 0 : Integer.parseInt(passStr);

        String failStr = findMatch(content, "Failed Scenarios\\s*:\\s*(\\d+)", "");
        report.info.failedScenarios = failStr.isEmpty() ? 0 : Integer.parseInt(failStr);

        report.info.successRate = findMatch(content, "Success Rate\\s*:\\s*([^\\n]+)", "");
        report.info.completedTimestamp = findMatch(content, "Completed Timestamp\\s*:\\s*([^\\n]+)", "");

        // Split Test Cases
        String[] chunks = content.split("(?=\\nTEST CASE ID\\s*:)");
        for (int i = 1; i < chunks.length; i++) {
            String chunk = chunks[i];
            Matcher tcIdMatcher = Pattern.compile("TEST CASE ID\\s*:\\s*(TC-\\d+)\\s*\\[(.*?)\\]").matcher(chunk);
            if (!tcIdMatcher.find()) {
                continue;
            }

            TestCaseData tc = new TestCaseData();
            tc.tcId = tcIdMatcher.group(1).trim();
            tc.tcName = tcIdMatcher.group(2).trim();

            tc.description = findMatch(chunk, "DESCRIPTION\\s*:\\s*([^\\n]+)", "");
            tc.timestamp = findMatch(chunk, "TIMESTAMP\\s*:\\s*([^\\n]+)", "");

            // Apply leave fields
            tc.leaveType = findMatch(chunk, "\\*\\s*Leave Type\\s*:\\s*([^\\n]+)", "");
            tc.fromDate = findMatch(chunk, "\\*\\s*From Date\\s*:\\s*([^\\n]+)", "");
            tc.toDate = findMatch(chunk, "\\*\\s*To Date\\s*:\\s*([^\\n]+)", "");
            tc.dayType = findMatch(chunk, "\\*\\s*Day Type\\s*:\\s*([^\\n]+)", "");
            tc.sessionVerified = findMatch(chunk, "\\*\\s*Session Dropdown Verified\\s*:\\s*([^\\n]+)", "");
            tc.remarks = findMatch(chunk, "\\*\\s*Remarks/Reason\\s*:\\s*(.*?)(?=\\n\\s*\\*\\s*File Attached|\\n\\s*\\*\\s*Expected Type|\\n-{20,})", Pattern.DOTALL, "").trim();
            tc.fileAttached = findMatch(chunk, "\\*\\s*File Attached\\s*:\\s*([^\\n]+)", "");

            // Login fields
            tc.loginId = findMatch(chunk, "\\*\\s*Login ID\\s*:\\s*([^\\n]+)", "");
            tc.password = findMatch(chunk, "\\*\\s*Password\\s*:\\s*([^\\n]+)", "");

            tc.expectedType = findMatch(chunk, "(?:\\*\\s*Expected Type|EXPECTED TYPE)\\s*:\\s*([^\\n]+)", "");

            // Outcomes
            tc.finalPageUrl = findMatch(chunk, "\\*\\s*Final Page URL\\s*:\\s*([^\\n]+)", "");
            tc.pageTitle = findMatch(chunk, "\\*\\s*Page Title\\s*:\\s*([^\\n]+)", "");
            tc.uiMessage = findMatch(chunk, "\\*\\s*UI Message Got\\s*:\\s*(.*?)(?=\\n\\s*\\*\\s*Final Verdict|\\n-{20,})", Pattern.DOTALL, "").trim();

            String verdictRaw = findMatch(chunk, "\\*\\s*Final Verdict\\s*:\\s*(.*?)(?=\\n-{20,}|\\n={20,}|$)", Pattern.DOTALL, "").trim();
            if (verdictRaw.startsWith("PASSED")) {
                tc.status = "PASSED";
            } else if (verdictRaw.startsWith("FAILED")) {
                tc.status = "FAILED";
            } else {
                tc.status = "INFO";
            }

            if (verdictRaw.contains("(") && verdictRaw.endsWith(")")) {
                tc.verdictDetails = verdictRaw.substring(verdictRaw.indexOf("(") + 1, verdictRaw.length() - 1).trim();
            } else {
                tc.verdictDetails = verdictRaw;
            }

            // Captured Network Activity
            String netText = findMatch(chunk, "CAPTURED NETWORK ACTIVITY:\\s*(.*?)(?=\\nUI & BROWSER OUTCOME:)", Pattern.DOTALL, "").trim();
            tc.requests = parseNetworkSection(netText);

            report.cases.add(tc);
        }

        // If summary wasn't present in the log, calculate dynamically
        if (report.info.totalScenarios == 0 && !report.cases.isEmpty()) {
            report.info.totalScenarios = report.cases.size();
            report.info.passedScenarios = (int) report.cases.stream().filter(c -> "PASSED".equals(c.status)).count();
            report.info.failedScenarios = report.info.totalScenarios - report.info.passedScenarios;
            report.info.successRate = (report.info.passedScenarios * 100 / report.info.totalScenarios) + "%";
        }

        return report;
    }

    private static List<NetworkRequest> parseNetworkSection(String text) {
        List<NetworkRequest> requests = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return requests;
        }

        String[] parts = ("\n" + text).split("(?=\\n\\s*\\[\\d+\\]\\s+HTTP REQUEST:)");
        for (String p : parts) {
            if (!p.contains("HTTP REQUEST:")) {
                continue;
            }
            NetworkRequest req = new NetworkRequest();
            req.num = findMatch(p, "\\[(\\d+)\\]", "");
            req.url = findMatch(p, "HTTP REQUEST:\\s*\\n\\s*URL\\s*:\\s*([^\\n]+)", "");
            req.method = findMatch(p, "Method\\s*:\\s*([^\\n]+)", "");
            req.params = findMatch(p, "(?:URL Query Parameters|Param / Body / FormData):\\s*(.*?)(?=\\n\\s*Request Headers:|\\n\\s*HTTP RESPONSE:|$)", Pattern.DOTALL, "").trim();
            req.headers = findMatch(p, "Request Headers:\\s*(\\{.*?\\})", Pattern.DOTALL, "").trim();
            req.status = findMatch(p, "Status Code\\s*:\\s*([^\\n]+)", "");
            req.mime = findMatch(p, "MIME Type\\s*:\\s*([^\\n]+)", "");
            req.body = findMatch(p, "Response Body Got:\\s*(.*?)(?=\\n\\s*(?:\\[\\d+\\]|UI & BROWSER OUTCOME:|$))", Pattern.DOTALL, "").trim();

            requests.add(req);
        }
        return requests;
    }

    private static String findMatch(String text, String regex, String def) {
        return findMatch(text, regex, 0, def);
    }

    private static String findMatch(String text, String regex, int flags, String def) {
        Matcher m = Pattern.compile(regex, flags).matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return def;
    }

    // ------------------------------------------------------------------------------------------------
    // Styles Helper
    // ------------------------------------------------------------------------------------------------

    private static class StyleContext {
        final XSSFWorkbook wb;
        final DefaultIndexedColorMap colorMap = new DefaultIndexedColorMap();

        final XSSFCellStyle navyHeader;
        final XSSFCellStyle subHeader;
        final XSSFCellStyle cardHeader;
        final XSSFCellStyle cardTotalVal;
        final XSSFCellStyle cardPassVal;
        final XSSFCellStyle cardFailVal;

        final XSSFCellStyle regularCellLeft;
        final XSSFCellStyle regularCellCenter;
        final XSSFCellStyle regularCellZebraLeft;
        final XSSFCellStyle regularCellZebraCenter;

        final XSSFCellStyle boldCellLeft;
        final XSSFCellStyle boldCellCenter;

        final XSSFCellStyle passCell;
        final XSSFCellStyle failCell;

        final XSSFColor thinBorderColor;

        StyleContext(XSSFWorkbook wb) {
            this.wb = wb;
            this.thinBorderColor = new XSSFColor(COLOR_BORDER, colorMap);

            // Fonts
            XSSFFont fontHeader = wb.createFont();
            fontHeader.setFontName("Segoe UI");
            fontHeader.setFontHeightInPoints((short) 11);
            fontHeader.setBold(true);
            fontHeader.setColor(new XSSFColor(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, colorMap));

            XSSFFont fontRegular = wb.createFont();
            fontRegular.setFontName("Segoe UI");
            fontRegular.setFontHeightInPoints((short) 10);
            fontRegular.setColor(new XSSFColor(new byte[]{(byte) 0x26, (byte) 0x26, (byte) 0x26}, colorMap));

            XSSFFont fontBold = wb.createFont();
            fontBold.setFontName("Segoe UI");
            fontBold.setFontHeightInPoints((short) 10);
            fontBold.setBold(true);
            fontBold.setColor(new XSSFColor(new byte[]{(byte) 0x1A, (byte) 0x1A, (byte) 0x1A}, colorMap));

            XSSFFont fontPass = wb.createFont();
            fontPass.setFontName("Segoe UI");
            fontPass.setFontHeightInPoints((short) 10);
            fontPass.setBold(true);
            fontPass.setColor(new XSSFColor(COLOR_PASS_TEXT, colorMap));

            XSSFFont fontFail = wb.createFont();
            fontFail.setFontName("Segoe UI");
            fontFail.setFontHeightInPoints((short) 10);
            fontFail.setBold(true);
            fontFail.setColor(new XSSFColor(COLOR_FAIL_TEXT, colorMap));

            // Navy Header Style
            navyHeader = wb.createCellStyle();
            navyHeader.setFillForegroundColor(new XSSFColor(COLOR_NAVY_HEADER, colorMap));
            navyHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            navyHeader.setFont(fontHeader);
            navyHeader.setAlignment(HorizontalAlignment.CENTER);
            navyHeader.setVerticalAlignment(VerticalAlignment.CENTER);
            navyHeader.setWrapText(true);
            applyBorders(navyHeader);

            // Sub Header Style
            subHeader = wb.createCellStyle();
            subHeader.setFillForegroundColor(new XSSFColor(COLOR_SUB_HEADER, colorMap));
            subHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            subHeader.setFont(fontHeader);
            subHeader.setAlignment(HorizontalAlignment.CENTER);
            subHeader.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorders(subHeader);

            // Card Header
            XSSFFont fontCardHdr = wb.createFont();
            fontCardHdr.setFontName("Segoe UI");
            fontCardHdr.setFontHeightInPoints((short) 9);
            fontCardHdr.setBold(true);
            fontCardHdr.setColor(new XSSFColor(COLOR_GRAY_TEXT, colorMap));

            cardHeader = wb.createCellStyle();
            cardHeader.setFillForegroundColor(new XSSFColor(COLOR_CARD_BG, colorMap));
            cardHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            cardHeader.setFont(fontCardHdr);
            cardHeader.setAlignment(HorizontalAlignment.CENTER);
            cardHeader.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorders(cardHeader);

            // Card Values
            cardTotalVal = createCardValStyle(wb, (short) 18, new XSSFColor(COLOR_CARD_BG, colorMap), new XSSFColor(new byte[]{(byte)0x1F, (byte)0x4E, (byte)0x79}, colorMap));
            cardPassVal = createCardValStyle(wb, (short) 18, new XSSFColor(COLOR_PASS_FILL, colorMap), new XSSFColor(COLOR_PASS_TEXT, colorMap));
            cardFailVal = createCardValStyle(wb, (short) 18, new XSSFColor(COLOR_FAIL_FILL, colorMap), new XSSFColor(COLOR_FAIL_TEXT, colorMap));

            // Data Cells
            regularCellLeft = wb.createCellStyle();
            regularCellLeft.setFont(fontRegular);
            regularCellLeft.setAlignment(HorizontalAlignment.LEFT);
            regularCellLeft.setVerticalAlignment(VerticalAlignment.CENTER);
            regularCellLeft.setWrapText(true);
            applyBorders(regularCellLeft);

            regularCellCenter = wb.createCellStyle();
            regularCellCenter.setFont(fontRegular);
            regularCellCenter.setAlignment(HorizontalAlignment.CENTER);
            regularCellCenter.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorders(regularCellCenter);

            // Zebra Rows
            XSSFColor zebraFill = new XSSFColor(COLOR_ZEBRA_BG, colorMap);
            regularCellZebraLeft = wb.createCellStyle();
            regularCellZebraLeft.cloneStyleFrom(regularCellLeft);
            regularCellZebraLeft.setFillForegroundColor(zebraFill);
            regularCellZebraLeft.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            applyBorders(regularCellZebraLeft);

            regularCellZebraCenter = wb.createCellStyle();
            regularCellZebraCenter.cloneStyleFrom(regularCellCenter);
            regularCellZebraCenter.setFillForegroundColor(zebraFill);
            regularCellZebraCenter.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            applyBorders(regularCellZebraCenter);

            // Bold
            boldCellLeft = wb.createCellStyle();
            boldCellLeft.setFont(fontBold);
            boldCellLeft.setAlignment(HorizontalAlignment.LEFT);
            boldCellLeft.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorders(boldCellLeft);

            boldCellCenter = wb.createCellStyle();
            boldCellCenter.setFont(fontBold);
            boldCellCenter.setAlignment(HorizontalAlignment.CENTER);
            boldCellCenter.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorders(boldCellCenter);

            // Pass / Fail
            passCell = wb.createCellStyle();
            passCell.setFont(fontPass);
            passCell.setFillForegroundColor(new XSSFColor(COLOR_PASS_FILL, colorMap));
            passCell.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            passCell.setAlignment(HorizontalAlignment.CENTER);
            passCell.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorders(passCell);

            failCell = wb.createCellStyle();
            failCell.setFont(fontFail);
            failCell.setFillForegroundColor(new XSSFColor(COLOR_FAIL_FILL, colorMap));
            failCell.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            failCell.setAlignment(HorizontalAlignment.CENTER);
            failCell.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorders(failCell);
        }

        private XSSFCellStyle createCardValStyle(XSSFWorkbook wb, short size, XSSFColor bg, XSSFColor fg) {
            XSSFFont f = wb.createFont();
            f.setFontName("Segoe UI");
            f.setFontHeightInPoints(size);
            f.setBold(true);
            f.setColor(fg);

            XSSFCellStyle s = wb.createCellStyle();
            s.setFillForegroundColor(bg);
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setFont(f);
            s.setAlignment(HorizontalAlignment.CENTER);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorders(s);
            return s;
        }

        private void applyBorders(XSSFCellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setTopBorderColor(thinBorderColor);
            style.setBottomBorderColor(thinBorderColor);
            style.setLeftBorderColor(thinBorderColor);
            style.setRightBorderColor(thinBorderColor);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Executive Dashboard Sheet
    // ------------------------------------------------------------------------------------------------

    private static void createDashboardSheet(XSSFWorkbook wb, StyleContext ctx, SuiteReport applyReport, SuiteReport loginReport) {
        XSSFSheet ws = wb.createSheet("Executive Dashboard");
        ws.setDisplayGridlines(true);

        SuiteInfo applyInfo = applyReport != null ? applyReport.info : new SuiteInfo();
        SuiteInfo loginInfo = loginReport != null ? loginReport.info : new SuiteInfo();
        List<TestCaseData> applyCases = applyReport != null ? applyReport.cases : Collections.emptyList();
        List<TestCaseData> loginCases = loginReport != null ? loginReport.cases : Collections.emptyList();

        // 1. Title Banner (A1:G1)
        ws.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));
        Row r1 = ws.createRow(0);
        r1.setHeightInPoints(40);
        Cell titleCell = r1.createCell(0);
        titleCell.setCellValue("SELENIUM AUTOMATION TEST EXECUTION DASHBOARD");

        XSSFFont titleFont = wb.createFont();
        titleFont.setFontName("Segoe UI");
        titleFont.setFontHeightInPoints((short) 16);
        titleFont.setBold(true);
        titleFont.setColor(new XSSFColor(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, ctx.colorMap));

        XSSFCellStyle titleStyle = wb.createCellStyle();
        titleStyle.cloneStyleFrom(ctx.navyHeader);
        titleStyle.setFont(titleFont);
        titleCell.setCellStyle(titleStyle);

        // 2. Subtitle Banner (A2:G2)
        ws.addMergedRegion(new CellRangeAddress(1, 1, 0, 6));
        Row r2 = ws.createRow(1);
        r2.setHeightInPoints(24);
        Cell subCell = r2.createCell(0);
        String targetUrl = !applyInfo.targetUrl.isEmpty() ? applyInfo.targetUrl : loginInfo.targetUrl;
        String browser = !applyInfo.browser.isEmpty() ? applyInfo.browser : loginInfo.browser;
        String completedTime = !applyInfo.completedTimestamp.isEmpty() ? applyInfo.completedTimestamp : loginInfo.completedTimestamp;
        if (completedTime.isEmpty()) {
            completedTime = !applyInfo.generatedDatetime.isEmpty() ? applyInfo.generatedDatetime : loginInfo.generatedDatetime;
        }
        subCell.setCellValue("Target Environment: " + targetUrl + "  |  Browser: " + browser + "  |  Executed: " + completedTime);

        XSSFFont subFont = wb.createFont();
        subFont.setFontName("Segoe UI");
        subFont.setFontHeightInPoints((short) 10);
        subFont.setItalic(true);
        subFont.setColor(new XSSFColor(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, ctx.colorMap));

        XSSFCellStyle subStyle = wb.createCellStyle();
        subStyle.cloneStyleFrom(ctx.subHeader);
        subStyle.setFont(subFont);
        subCell.setCellStyle(subStyle);

        // 3. Metric Summary Cards (Rows 4 & 5 -> 0-based index 3 & 4)
        int totalAll = applyCases.size() + loginCases.size();
        long passAll = applyCases.stream().filter(c -> "PASSED".equals(c.status)).count()
                     + loginCases.stream().filter(c -> "PASSED".equals(c.status)).count();
        long failAll = totalAll - passAll;
        double rateAll = totalAll > 0 ? ((double) passAll / totalAll * 100.0) : 0.0;
        String rateAllStr = String.format("%.1f%%", rateAll);

        Row r4 = ws.createRow(3);
        r4.setHeightInPoints(26);
        Row r5 = ws.createRow(4);
        r5.setHeightInPoints(34);

        // Cards layout:
        // Col 1-2 (B-C): TOTAL TEST CASES
        // Col 3 (D): PASSED SCENARIOS
        // Col 4 (E): FAILED SCENARIOS
        // Col 5-6 (F-G): OVERALL SUCCESS RATE
        createCard(ws, ctx, 1, 2, "TOTAL TEST CASES", String.valueOf(totalAll), ctx.cardHeader, ctx.cardTotalVal);
        createCard(ws, ctx, 3, 3, "PASSED SCENARIOS", String.valueOf(passAll), ctx.cardHeader, ctx.cardPassVal);
        createCard(ws, ctx, 4, 4, "FAILED SCENARIOS", String.valueOf(failAll), ctx.cardHeader, ctx.cardFailVal);
        createCard(ws, ctx, 5, 6, "OVERALL SUCCESS RATE", rateAllStr, ctx.cardHeader, ctx.cardTotalVal);

        // 4. Suite Execution Breakdown Table (Rows 7 onwards -> index 6)
        Row r7 = ws.createRow(6);
        r7.setHeightInPoints(25);
        Cell breakdownTitle = r7.createCell(1);
        breakdownTitle.setCellValue("SUITE EXECUTION BREAKDOWN");
        XSSFFont subTitleFont = wb.createFont();
        subTitleFont.setFontName("Segoe UI");
        subTitleFont.setFontHeightInPoints((short) 11);
        subTitleFont.setBold(true);
        subTitleFont.setColor(new XSSFColor(COLOR_SUB_HEADER, ctx.colorMap));
        XSSFCellStyle subTitleStyle = wb.createCellStyle();
        subTitleStyle.setFont(subTitleFont);
        breakdownTitle.setCellStyle(subTitleStyle);

        // Header (Row 8 -> index 7)
        Row r8 = ws.createRow(7);
        r8.setHeightInPoints(25);
        String[] tableHeaders = {"Suite Name", "Class / Component", "Total Tests", "Passed", "Failed", "Success Rate"};
        for (int i = 0; i < tableHeaders.length; i++) {
            Cell c = r8.createCell(i + 1);
            c.setCellValue(tableHeaders[i]);
            c.setCellStyle(ctx.navyHeader);
        }

        // Table Rows
        long applyPass = applyCases.stream().filter(c -> "PASSED".equals(c.status)).count();
        long loginPass = loginCases.stream().filter(c -> "PASSED".equals(c.status)).count();

        Object[][] suitesData = new Object[][]{
            {"Employee Leave Application", applyInfo.suiteTitle, applyCases.size(), applyPass, applyCases.size() - applyPass, applyInfo.successRate},
            {"Employee Portal Authentication", loginInfo.suiteTitle, loginCases.size(), loginPass, loginCases.size() - loginPass, loginInfo.successRate},
            {"Consolidated Total", "All Automated Suites", totalAll, passAll, failAll, rateAllStr}
        };

        int currRow = 8;
        for (int r = 0; r < suitesData.length; r++) {
            Row row = ws.createRow(currRow);
            row.setHeightInPoints(22);
            boolean isTotal = (r == suitesData.length - 1);
            for (int col = 0; col < 6; col++) {
                Cell cell = row.createCell(col + 1);
                Object val = suitesData[r][col];
                if (val instanceof Number) {
                    cell.setCellValue(((Number) val).doubleValue());
                } else {
                    cell.setCellValue(String.valueOf(val != null ? val : ""));
                }

                if (isTotal) {
                    cell.setCellStyle(col >= 2 ? ctx.boldCellCenter : ctx.boldCellLeft);
                } else if (r % 2 == 1) {
                    cell.setCellStyle(col >= 2 ? ctx.regularCellZebraCenter : ctx.regularCellZebraLeft);
                } else {
                    cell.setCellStyle(col >= 2 ? ctx.regularCellCenter : ctx.regularCellLeft);
                }
            }
            currRow++;
        }

        // 5. High-Level Scenario Results Matrix Table (Row 14 -> index 13)
        currRow += 2;
        Row r14 = ws.createRow(currRow++);
        r14.setHeightInPoints(25);
        Cell matrixTitle = r14.createCell(1);
        matrixTitle.setCellValue("HIGH-LEVEL SCENARIO RESULTS MATRIX");
        matrixTitle.setCellStyle(subTitleStyle);

        Row r15 = ws.createRow(currRow++);
        r15.setHeightInPoints(25);
        String[] matrixHeaders = {"Suite", "Test Case ID", "Scenario Title", "Input Category", "Network Requests", "Verdict"};
        for (int i = 0; i < matrixHeaders.length; i++) {
            Cell c = r15.createCell(i + 1);
            c.setCellValue(matrixHeaders[i]);
            c.setCellStyle(ctx.navyHeader);
        }

        // Add Apply Leave Cases
        for (TestCaseData tc : applyCases) {
            Row row = ws.createRow(currRow++);
            row.setHeightInPoints(20);
            populateMatrixRow(row, ctx, "Apply Leave", tc);
        }

        // Add Login Cases
        for (TestCaseData tc : loginCases) {
            Row row = ws.createRow(currRow++);
            row.setHeightInPoints(20);
            populateMatrixRow(row, ctx, "Login", tc);
        }

        autoFitColumns(ws, 7, 14, 50);
    }

    private static void populateMatrixRow(Row row, StyleContext ctx, String suiteName, TestCaseData tc) {
        Cell c1 = row.createCell(1); c1.setCellValue(suiteName); c1.setCellStyle(ctx.regularCellLeft);
        Cell c2 = row.createCell(2); c2.setCellValue(tc.tcId); c2.setCellStyle(ctx.regularCellCenter);
        Cell c3 = row.createCell(3); c3.setCellValue(tc.tcName); c3.setCellStyle(ctx.regularCellLeft);
        Cell c4 = row.createCell(4); c4.setCellValue(tc.expectedType); c4.setCellStyle(ctx.regularCellLeft);
        Cell c5 = row.createCell(5); c5.setCellValue(tc.requests.size()); c5.setCellStyle(ctx.regularCellCenter);
        Cell c6 = row.createCell(6); c6.setCellValue(tc.status);
        c6.setCellStyle("PASSED".equalsIgnoreCase(tc.status) ? ctx.passCell : ctx.failCell);
    }

    private static void createCard(XSSFSheet ws, StyleContext ctx, int startCol, int endCol, String title, String val, XSSFCellStyle titleStyle, XSSFCellStyle valStyle) {
        if (startCol != endCol) {
            ws.addMergedRegion(new CellRangeAddress(3, 3, startCol, endCol));
            ws.addMergedRegion(new CellRangeAddress(4, 4, startCol, endCol));
        }

        Row rHdr = ws.getRow(3);
        Row rVal = ws.getRow(4);

        for (int col = startCol; col <= endCol; col++) {
            Cell cTop = rHdr.createCell(col);
            cTop.setCellStyle(titleStyle);
            if (col == startCol) cTop.setCellValue(title);

            Cell cBot = rVal.createCell(col);
            cBot.setCellStyle(valStyle);
            if (col == startCol) cBot.setCellValue(val);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Apply Leave Sheets
    // ------------------------------------------------------------------------------------------------

    private static void buildApplyLeaveCasesSheet(XSSFWorkbook wb, StyleContext ctx, List<TestCaseData> cases) {
        XSSFSheet ws = wb.createSheet("Apply Leave - Test Cases");
        ws.setDisplayGridlines(true);
        ws.createFreezePane(0, 1);

        String[] headers = {
            "Test Case ID", "Scenario Name", "Description", "Timestamp",
            "Leave Type", "From Date", "To Date", "Day Type", "Session Verified",
            "Remarks / Reason", "File Attached", "Expected Type",
            "Final Page URL", "Page Title", "UI Message Got", "Status", "Verdict Details"
        };

        Row r0 = ws.createRow(0);
        r0.setHeightInPoints(28);
        for (int i = 0; i < headers.length; i++) {
            Cell c = r0.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(ctx.navyHeader);
        }

        int rowIdx = 1;
        for (TestCaseData c : cases) {
            Row r = ws.createRow(rowIdx++);
            r.setHeightInPoints(24);
            boolean zebra = (rowIdx % 2 == 1);

            String[] vals = {
                c.tcId, c.tcName, c.description, c.timestamp,
                c.leaveType, c.fromDate, c.toDate, c.dayType, c.sessionVerified,
                c.remarks, c.fileAttached, c.expectedType,
                c.finalPageUrl, c.pageTitle, c.uiMessage, c.status, c.verdictDetails
            };

            for (int col = 0; col < vals.length; col++) {
                Cell cell = r.createCell(col);
                cell.setCellValue(vals[col] != null ? vals[col] : "");

                if (col == 15) { // Status
                    cell.setCellStyle("PASSED".equalsIgnoreCase(c.status) ? ctx.passCell : ctx.failCell);
                } else if (col == 0 || col == 3 || col == 5 || col == 6 || col == 7 || col == 8 || col == 10) {
                    cell.setCellStyle(zebra ? ctx.regularCellZebraCenter : ctx.regularCellCenter);
                } else {
                    cell.setCellStyle(zebra ? ctx.regularCellZebraLeft : ctx.regularCellLeft);
                }
            }
        }

        if (!cases.isEmpty()) {
            ws.setAutoFilter(new CellRangeAddress(0, cases.size(), 0, headers.length - 1));
        }
        autoFitColumns(ws, headers.length, 12, 45);
    }

    private static void buildApplyLeaveNetworkSheet(XSSFWorkbook wb, StyleContext ctx, List<TestCaseData> cases) {
        XSSFSheet ws = wb.createSheet("Apply Leave - Network & APIs");
        ws.setDisplayGridlines(true);
        ws.createFreezePane(0, 1);

        String[] headers = {
            "Test Case ID", "Scenario Name", "Req #", "HTTP Method", "Target URL / Endpoint",
            "URL Query Params / Body", "Request Headers", "Status Code", "MIME Type", "Response Body / Message"
        };

        Row r0 = ws.createRow(0);
        r0.setHeightInPoints(28);
        for (int i = 0; i < headers.length; i++) {
            Cell c = r0.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(ctx.navyHeader);
        }

        int currRow = 1;
        for (TestCaseData c : cases) {
            if (c.requests.isEmpty()) {
                Row r = ws.createRow(currRow++);
                r.setHeightInPoints(22);
                String[] vals = {c.tcId, c.tcName, "-", "-", "[No HTTP network transactions recorded]", "-", "-", "-", "-", "-"};
                for (int col = 0; col < vals.length; col++) {
                    Cell cell = r.createCell(col);
                    cell.setCellValue(vals[col]);
                    cell.setCellStyle((col == 0 || col == 2 || col == 3 || col == 7) ? ctx.regularCellCenter : ctx.regularCellLeft);
                }
                continue;
            }

            for (NetworkRequest req : c.requests) {
                Row r = ws.createRow(currRow++);
                r.setHeightInPoints(26);
                boolean zebra = (currRow % 2 == 1);

                String[] vals = {
                    c.tcId, c.tcName, "[" + req.num + "]", req.method, req.url,
                    req.params, req.headers, req.status, req.mime, req.body
                };

                for (int col = 0; col < vals.length; col++) {
                    Cell cell = r.createCell(col);
                    cell.setCellValue(vals[col] != null ? vals[col] : "");

                    if (col == 7) { // Status Code
                        if (req.status.contains("200")) {
                            cell.setCellStyle(ctx.passCell);
                        } else if (req.status.contains("400") || req.status.contains("500") || req.status.contains("404")) {
                            cell.setCellStyle(ctx.failCell);
                        } else {
                            cell.setCellStyle(zebra ? ctx.regularCellZebraCenter : ctx.regularCellCenter);
                        }
                    } else if (col == 0 || col == 2 || col == 3 || col == 8) {
                        cell.setCellStyle(zebra ? ctx.regularCellZebraCenter : ctx.regularCellCenter);
                    } else {
                        cell.setCellStyle(zebra ? ctx.regularCellZebraLeft : ctx.regularCellLeft);
                    }
                }
            }
        }

        if (currRow > 1) {
            ws.setAutoFilter(new CellRangeAddress(0, currRow - 1, 0, headers.length - 1));
        }
        autoFitColumns(ws, headers.length, 12, 50);
    }

    // ------------------------------------------------------------------------------------------------
    // Login Sheets
    // ------------------------------------------------------------------------------------------------

    private static void buildLoginCasesSheet(XSSFWorkbook wb, StyleContext ctx, List<TestCaseData> cases) {
        XSSFSheet ws = wb.createSheet("Login - Test Cases");
        ws.setDisplayGridlines(true);
        ws.createFreezePane(0, 1);

        String[] headers = {
            "Test Case ID", "Scenario Name", "Description", "Timestamp",
            "Login ID", "Password", "Expected Type",
            "Final Page URL", "Page Title", "UI Message Got", "Status", "Verdict Details"
        };

        Row r0 = ws.createRow(0);
        r0.setHeightInPoints(28);
        for (int i = 0; i < headers.length; i++) {
            Cell c = r0.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(ctx.navyHeader);
        }

        int rowIdx = 1;
        for (TestCaseData c : cases) {
            Row r = ws.createRow(rowIdx++);
            r.setHeightInPoints(24);
            boolean zebra = (rowIdx % 2 == 1);

            String[] vals = {
                c.tcId, c.tcName, c.description, c.timestamp,
                c.loginId, c.password, c.expectedType,
                c.finalPageUrl, c.pageTitle, c.uiMessage, c.status, c.verdictDetails
            };

            for (int col = 0; col < vals.length; col++) {
                Cell cell = r.createCell(col);
                cell.setCellValue(vals[col] != null ? vals[col] : "");

                if (col == 10) { // Status
                    cell.setCellStyle("PASSED".equalsIgnoreCase(c.status) ? ctx.passCell : ctx.failCell);
                } else if (col == 0 || col == 3) {
                    cell.setCellStyle(zebra ? ctx.regularCellZebraCenter : ctx.regularCellCenter);
                } else {
                    cell.setCellStyle(zebra ? ctx.regularCellZebraLeft : ctx.regularCellLeft);
                }
            }
        }

        if (!cases.isEmpty()) {
            ws.setAutoFilter(new CellRangeAddress(0, cases.size(), 0, headers.length - 1));
        }
        autoFitColumns(ws, headers.length, 12, 45);
    }

    private static void buildLoginNetworkSheet(XSSFWorkbook wb, StyleContext ctx, List<TestCaseData> cases) {
        XSSFSheet ws = wb.createSheet("Login - Network & APIs");
        ws.setDisplayGridlines(true);
        ws.createFreezePane(0, 1);

        String[] headers = {
            "Test Case ID", "Scenario Name", "Req #", "HTTP Method", "Target URL / Endpoint",
            "URL Query Params / Body", "Request Headers", "Status Code", "MIME Type", "Response Body / Message"
        };

        Row r0 = ws.createRow(0);
        r0.setHeightInPoints(28);
        for (int i = 0; i < headers.length; i++) {
            Cell c = r0.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(ctx.navyHeader);
        }

        int currRow = 1;
        for (TestCaseData c : cases) {
            if (c.requests.isEmpty()) {
                Row r = ws.createRow(currRow++);
                r.setHeightInPoints(22);
                String[] vals = {c.tcId, c.tcName, "-", "-", "[Notice: No HTTP requests captured for this interaction, or blocked by client-side validation]", "-", "-", "-", "-", "-"};
                for (int col = 0; col < vals.length; col++) {
                    Cell cell = r.createCell(col);
                    cell.setCellValue(vals[col]);
                    cell.setCellStyle((col == 0 || col == 2 || col == 3 || col == 7) ? ctx.regularCellCenter : ctx.regularCellLeft);
                }
                continue;
            }

            for (NetworkRequest req : c.requests) {
                Row r = ws.createRow(currRow++);
                r.setHeightInPoints(26);
                boolean zebra = (currRow % 2 == 1);

                String[] vals = {
                    c.tcId, c.tcName, "[" + req.num + "]", req.method, req.url,
                    req.params, req.headers, req.status, req.mime, req.body
                };

                for (int col = 0; col < vals.length; col++) {
                    Cell cell = r.createCell(col);
                    cell.setCellValue(vals[col] != null ? vals[col] : "");

                    if (col == 7) { // Status Code
                        if (req.status.contains("200")) {
                            cell.setCellStyle(ctx.passCell);
                        } else if (req.status.contains("400") || req.status.contains("500") || req.status.contains("404")) {
                            cell.setCellStyle(ctx.failCell);
                        } else {
                            cell.setCellStyle(zebra ? ctx.regularCellZebraCenter : ctx.regularCellCenter);
                        }
                    } else if (col == 0 || col == 2 || col == 3 || col == 8) {
                        cell.setCellStyle(zebra ? ctx.regularCellZebraCenter : ctx.regularCellCenter);
                    } else {
                        cell.setCellStyle(zebra ? ctx.regularCellZebraLeft : ctx.regularCellLeft);
                    }
                }
            }
        }

        if (currRow > 1) {
            ws.setAutoFilter(new CellRangeAddress(0, currRow - 1, 0, headers.length - 1));
        }
        autoFitColumns(ws, headers.length, 12, 50);
    }

    // ------------------------------------------------------------------------------------------------
    // Column Width Helpers
    // ------------------------------------------------------------------------------------------------

    private static void autoFitColumns(XSSFSheet sheet, int totalCols, int minCharWidth, int maxCharWidth) {
        for (int i = 0; i < totalCols; i++) {
            sheet.autoSizeColumn(i);
            int currentWidth = sheet.getColumnWidth(i);
            int minWidth = minCharWidth * 256;
            int maxWidth = maxCharWidth * 256;

            if (currentWidth < minWidth) {
                sheet.setColumnWidth(i, minWidth);
            } else if (currentWidth > maxWidth) {
                sheet.setColumnWidth(i, maxWidth);
            }
        }
    }
}
