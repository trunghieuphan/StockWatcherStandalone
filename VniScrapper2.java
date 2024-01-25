import org.apache.commons.io.FileUtils;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.logging.Formatter;
import java.util.logging.*;
import java.util.stream.Collectors;

public class VniScrapper2 {

    private static final Logger LOGGER = Logger.getLogger(VniScrapper2.class.getName());
    private static final Properties props = new Properties();

    public VniScrapper2() {
    }

    public Properties getProps() {
        return props;
    }

    public static void main(String[] args) throws IOException {

        if(args == null || args.length == 0){
            return;
        }

        VniScrapper2 scrapper = new VniScrapper2();
        scrapper.loadProperties(args);
        scrapper.configLogger();

        List<String> stockList = new ArrayList<>();
        for(String arg : args){
            arg = arg.trim();
            if(arg.contains("=")){
                //Ignore app settings/configuration
                continue;
            }
            //Group of stocks
            else if(arg.startsWith("NHOM_NGANH")){
                if(arg.equals("NHOM_NGANH_TAT_CA")){
                    Object[] keys = scrapper.getProps().keySet().toArray();
                    for(Object key : keys){
                        if(key.toString().startsWith("NHOM_NGANH")){
                            stockList.addAll(Arrays.asList(scrapper.getProps().getProperty(key.toString()).split(",")));
                        }
                    }
                    break;
                }
                else{
                    stockList.addAll(Arrays.asList(scrapper.getProps().getProperty(arg).split(",")));
                }
            }
            //Stock code
            else{
                stockList.add(arg);
            }
        }

        //round 1
        LOGGER.log(Level.INFO, "Starting round 1 with " + stockList.size() + " stocks");
        cleanUp();
        scrapper.doWork(stockList);

        //round 2
        List<String> completedCodes = Arrays.stream(scrapper.todayFolder().list()).map(fileName -> fileName.substring(0, fileName.indexOf("."))).collect(Collectors.toList());
        stockList.removeAll(completedCodes);
        if(stockList.size() > 0){
            LOGGER.log(Level.INFO, "Starting round 2 with " + stockList.size() + " stocks remaining");
            cleanUp();
            scrapper.doWork(stockList);
        }

        //round 3
        completedCodes = Arrays.stream(scrapper.todayFolder().list()).map(fileName -> fileName.substring(0, fileName.indexOf("."))).collect(Collectors.toList());
        stockList.removeAll(completedCodes);
        if(stockList.size() > 0){
            LOGGER.log(Level.INFO, "Starting round 3 with " + stockList.size() + " stocks remaining");
            cleanUp();
            scrapper.doWork(stockList);
        }

        String summary = stockList.isEmpty() ? "DONE !!!" : ("There are " + stockList.size() + " stocks failed: " + stockList.stream().collect(Collectors.joining(", ")));

        LOGGER.log(Level.INFO, summary);

        //shutdownMachine();
    }

    private void doWork(List<String> stockList) throws IOException {

        File todayFolder = todayFolder();

        int MAX_RETRY_TIMES = Integer.parseInt(props.getProperty("RETRY_TIMES"));

        int RETRY_TIMES = 0;

        while(RETRY_TIMES < MAX_RETRY_TIMES){
            try{

                WebDriver driver = chromeDriver();

                driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(90));

                driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(90));

                driver.manage().window().maximize();

                driver.get("https://stockboard.sbsc.com.vn/technical-analysis");

                for(String stockCode : stockList){
                    //Check if this stock already be scrapped?
                    File stockDataFile = new File(todayFolder, stockCode + ".csv");
                    if(!stockDataFile.exists()){
                        scrappingStockCode(driver, stockCode, todayFolder);
                    }
                }

                //Happy case: stop retry
                MAX_RETRY_TIMES = 0;

                driver.quit();
            }
            catch (WebDriverException webDriverEx){
                webDriverEx.printStackTrace();
                LOGGER.log(Level.SEVERE, webDriverEx.getMessage());
                cleanUp();
                RETRY_TIMES += 1;
                System.out.println("Retry time: " + RETRY_TIMES);
                LOGGER.log(Level.INFO, "Retry time: " + RETRY_TIMES);
                if(RETRY_TIMES == MAX_RETRY_TIMES){
                    logOutOfRetry(webDriverEx.getMessage(), todayFolder);
                }
            }
        }
    }

    private void scrappingStockCode(WebDriver driver, String stockCode, File todayFolder) throws IOException {

        System.out.println("ScrappingStockCode: " + stockCode);
        LOGGER.log(Level.INFO, "ScrappingStockCode: " + stockCode);

        WebElement txtSearch = driver.findElement(By.xpath("//input[@type='search']"));
        txtSearch.clear();
        txtSearch.sendKeys(stockCode);
        txtSearch.sendKeys(Keys.RETURN);

        String innerText = getTableText(driver);

        if(!innerText.equals("")){
            FileUtils.writeStringToFile(new File(todayFolder, stockCode + ".csv"), innerText, "UTF-8");
        }
        else{
            System.out.println(stockCode + " has no data");
            LOGGER.log(Level.INFO, stockCode + " has no data");
        }
        /*
        StringBuilder sb = new StringBuilder();

        for(int i=0; i < rows.size(); i++){
            WebElement row = rows.get(i);
            Actions a = new Actions(driver);
            a.moveToElement(row);
            a.perform();
            String text = row.getText();
            sb.append(text);
            sb.append("\n");
        }

        FileUtils.writeStringToFile(new File(todayFolder, stockCode + ".csv"), sb.toString(), "UTF-8");
        */
    }

    private String getTableText(WebDriver driver){
        WebElement tbody = driver.findElement(By.xpath("//tbody[@class='w-full chart']"));
        JavascriptExecutor js = (JavascriptExecutor)driver;
        for(int i=0; i < 10; i++){
            js.executeScript("arguments[0].scrollIntoView(false);", tbody);
            Object innerText = js.executeScript("return arguments[0].innerText", tbody);
            if(innerText != null && !innerText.toString().isBlank()){
                return innerText.toString();
            }
            else {
                try { Thread.sleep(1000); } catch (InterruptedException inex) {}
            }
        }
        return "";
    }

    private WebDriver chromeDriver() {
        if(System.getProperty("webdriver.chrome.driver") == null){
            System.setProperty("webdriver.chrome.driver", props.getProperty("CHROME_DRIVER_PATH"));
        }

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-dev-shm-usage");

        WebDriver driver = new ChromeDriver(options);
        return driver;
    }

    private static void cleanUp() {
        try{
            boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
            if (isWindows) {
                Process process = Runtime.getRuntime().exec("taskkill /f /im chromedriver.exe");
                process.waitFor();
                process = Runtime.getRuntime().exec("taskkill /f /im chrome.exe");
                process.waitFor();
            } else {
                Process process = Runtime.getRuntime().exec("sudo pkill chrome");
                process.waitFor();
            }
        }
        catch(IOException | InterruptedException ex){
            ex.printStackTrace();
            LOGGER.log(Level.SEVERE, ex.getMessage());
        }
    }

    private void loadProperties(String[] args) throws IOException {
        String propFile = "StockWatcher.properties";
        for(String arg : args) {
            arg = arg.trim();
            //Override properties file name
            if (arg.startsWith("PROPS_FILE=") || arg.startsWith("PROPERTIES_FILE=")
                    || arg.startsWith("props_file=") || arg.startsWith("properties_file=")) {
                propFile = arg.split("=")[1];
                break;
            }
        }
        InputStream is = new FileInputStream(new File(getCurrentClassFilePath(), propFile));
        props.load(is);
        is.close();
    }

    private void configLogger() throws IOException {
        String logTo = props.getProperty("LOG_TO");
        if(logTo == null || logTo.isBlank()){
            logTo = getCurrentClassFilePath().getAbsolutePath();
        }
        String fileName = "crontab" + new SimpleDateFormat("yyyyMMdd").format(Calendar.getInstance().getTime()) + ".log";
        Handler fileHandler = new FileHandler(logTo + "/" + fileName);
        fileHandler.setLevel(Level.ALL);
        Formatter simpleFormatter = new SimpleFormatter();
        fileHandler.setFormatter(simpleFormatter);

        LOGGER.addHandler(fileHandler);
        LOGGER.setLevel(Level.ALL);
    }

    private File getCurrentClassFilePath(){
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        URL url = loader.getResource(".");
        return new File(url.getFile());
    }

    private void logOutOfRetry(String message, File todayFolder){
        try {
            FileUtils.writeStringToFile(new File(todayFolder.getParentFile(), todayFolder.getName() + ".err.txt"), message, "UTF-8");
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    private static void shutdownMachine(){
        try{
            boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
            if (isWindows) {
            } else {
                Runtime.getRuntime().exec("sudo shutdown -h +1");
            }
        }
        catch(IOException ex){
            ex.printStackTrace();
            LOGGER.log(Level.SEVERE, ex.getMessage());
        }
    }

    private File todayFolder(){
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        File todayFolder = new File(props.getProperty("STOCK_STORAGE_FOLDER"), sdf.format(Calendar.getInstance().getTime()));
        if(!todayFolder.exists()){
            todayFolder.mkdir();
        }
        return todayFolder;
    }
}
