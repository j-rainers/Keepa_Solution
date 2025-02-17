package test;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.keepa.api.backend.KeepaAPI;
import com.keepa.api.backend.structs.AmazonLocale;
import com.keepa.api.backend.structs.Request;

import io.github.cdimascio.dotenv.Dotenv;

public class App_IT {
    private static final int BATCH_SIZE = 1;
    private static final int MAX_ASINS = 1000;  // Limit to first 1000 ASINs
    private static final OutputManager out = OutputManager.getInstance();

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.load();
        String apiKey = dotenv.get("API_KEY");
        KeepaAPI api = new KeepaAPI(apiKey);

        processBestSellersForLocale(api, AmazonLocale.IT, 412609031, "IT Keepa Data");
    }

    private static String getCurrentTime() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        return dtf.format(LocalDateTime.now());
    }

    private static void println(String message) {
        String formattedMessage = "[" + getCurrentTime() + "] (IT) " + message;
        out.println(formattedMessage);
    }

    private static void processBestSellersForLocale(KeepaAPI api, AmazonLocale locale, int nodeId, String responseFileName) {
        Request bestSellersRequest = Request.getBestSellersRequest(locale, nodeId);

        api.sendRequest(bestSellersRequest)
            .done(result -> {
                // Write the response to a file
                try (FileWriter writer = new FileWriter(responseFileName + ".txt")) {
                    writer.write(result.toString());
                    println("Data saved to: " + responseFileName + ".txt");
                    writer.flush();
                } catch (IOException e) {
                    println("Error writing to file: " + e.getMessage());
                }

                // Read the response back from the file and process it
                try (BufferedReader br = new BufferedReader(new FileReader(responseFileName + ".txt"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }

                    String jsonResponseString = sb.toString();
                    JSONObject jsonResponse = new JSONObject(jsonResponseString);

                    if (jsonResponse.has("bestSellersList")) {
                        JSONArray asinList = jsonResponse.getJSONObject("bestSellersList").getJSONArray("asinList");

                        String asinFileName = responseFileName + "_asinList.txt";

                        // Write the asinList to a separate file
                        try (FileWriter asinWriter = new FileWriter(asinFileName)) {
                            for (int i = 0; i < asinList.length(); i++) {
                                asinWriter.write(asinList.getString(i) + System.lineSeparator());
                            }
                            asinWriter.flush();
                            println("ASIN list saved to: " + asinFileName);
                        } catch (IOException e) {
                            println("Error writing ASIN list to file: " + e.getMessage());
                        }

                        // Process the first 1000 ASINs in batches for the current locale
                        processAsinBatches(asinFileName, api, locale);
                    } else {
                        println("bestSellersList not found in the response.");
                    }
                } catch (IOException | JSONException e) {
                    println("Error processing JSON from file: " + e.getMessage());
                }
            })
            .fail(failure -> println("Request failed: " + failure));
    }

    private static void processAsinBatches(String asinFileName, KeepaAPI api, AmazonLocale locale) {
        deleteOldData(locale);

        List<String> asins = readAsinListFromFile(asinFileName);
        if (asins.size() > MAX_ASINS) {
            asins = asins.subList(0, MAX_ASINS);
        }
        int totalAsins = asins.size();
        println("Total ASINs to process: " + totalAsins);
        Collections.reverse(asins);

        // Process ASINs in batches
        for (int start = 0; start < totalAsins; start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, totalAsins);
            List<String> batch = asins.subList(start, end);
            String asinBatch = String.join(",", batch);
            // Request for seller data first
            Request sellersRequest = Request.getProductRequest(locale, 90, 40, asinBatch);
    
            api.sendRequest(sellersRequest)
                .done(sellersResult -> {
                    try (FileWriter sellerWriter = new FileWriter("sellers_data.json")) {
                        sellerWriter.write(sellersResult.toString());
                        println("Seller data saved to file.");
                        sellerWriter.flush();
    
                        // Read and process the seller data
                        try (FileReader sellersReader = new FileReader("sellers_data.json")) {
                            JSONTokener sellersTokener = new JSONTokener(sellersReader);
                            JSONObject sellersJsonObject = new JSONObject(sellersTokener);
    
                            JSONArray sellerProducts = sellersJsonObject.getJSONArray("products");
                            LinkedHashMap<String, Integer> asinToWinnerCount = new LinkedHashMap<>();
    
                            // Collect ASINs and their winner counts from seller data
                            for (int j = 0; j < sellerProducts.length(); j++) {
                                JSONObject sellerProduct = sellerProducts.getJSONObject(j);
                                String asin = sellerProduct.optString("asin");
                                JSONObject sellerStats = sellerProduct.optJSONObject("stats");
                                JSONObject sellerBuyBoxStats = sellerStats != null ? sellerStats.optJSONObject("buyBoxStats") : null;
                                int winnerCount90 = sellerBuyBoxStats != null ? sellerBuyBoxStats.length() : 0;
                                asinToWinnerCount.put(asin, winnerCount90);
                            }
    
                            // Request for product data with the same batch of ASINs
                            String productAsinBatch = String.join(",", asinToWinnerCount.keySet());
                            Request productRequest = Request.getProductRequest(locale, 30, 40, productAsinBatch);
                            productRequest.parameter.put("stock", "1");
    
                            api.sendRequest(productRequest)
                                .done(productResult -> {
                                    try (FileWriter writer = new FileWriter("batch_data.json")) {
                                        writer.write(productResult.toString());
                                        println("Batch data saved to file.");
                                        writer.flush();
    
                                        // Read and process the JSON data for the batch
                                        try (FileReader reader = new FileReader("batch_data.json")) {
                                            JSONTokener tokener = new JSONTokener(reader);
                                            JSONObject jsonObject = new JSONObject(tokener);
    
                                            // Process the "products" array at the top level of the response
                                            JSONArray products = jsonObject.getJSONArray("products");
    
                                            // Map to store product data by ASIN
                                            LinkedHashMap<String, JSONObject> asinToProductData = new LinkedHashMap<>();
                                            for (int k = 0; k < products.length(); k++) {
                                                JSONObject product = products.getJSONObject(k);
                                                String asin = product.optString("asin");
                                                asinToProductData.put(asin, product);
                                            }
    
                                            // Process each product in the order of ASINs from the original list
                                            List<CompletableFuture<Void>> futures = new ArrayList<>();
                                            for (String asin : batch) {
                                                JSONObject product = asinToProductData.get(asin);
                                                if (product != null) {
                                                    int winnerCount90 = asinToWinnerCount.getOrDefault(asin, 0);
    
                                                    // Use CompletableFuture to handle async operation
                                                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                                                        processProductData(product, winnerCount90, locale);
                                                    });
                                                    futures.add(future);
                                                }
                                            }
    
                                            // Wait for all futures to complete
                                            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                                        } catch (Exception e) {
                                            println("Error parsing batch product JSON: " + e.getMessage());
                                        }
                                    } catch (IOException e) {
                                        println("Error writing batch data: " + e.getMessage());
                                    }
                                })
                                .fail(failure -> println("Request failed: " + failure));
                        } catch (Exception e) {
                            println("Error parsing seller product JSON: " + e.getMessage());
                        }
                    } catch (IOException e) {
                        println("Error writing seller batch data: " + e.getMessage());
                    }
                })
                .fail(failure -> println("Request failed: " + failure));
    
            try {
                Thread.sleep(30000); // Sleep between batches to avoid hitting API rate limits
            } catch (InterruptedException e) {
                println("Thread interrupted: " + e.getMessage());
            }
        }
    }
    
    private static List<String> readAsinListFromFile(String asinFileName) {
        List<String> asins = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(asinFileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
                asins.add(line.trim());
            }
        } catch (IOException e) {
            println("Error reading ASIN file: " + e.getMessage());
        }
        return asins;
    }

    private static void processProductData(JSONObject product, int winnerCount90, AmazonLocale locale) {
        try {
            // Initialize variables to capture each field
            String title = product.optString("title", "N/A");
            JSONObject stats = product.optJSONObject("stats");
            JSONObject fbaFees = product.optJSONObject("fbaFees");

            JSONArray current = stats != null ? stats.optJSONArray("current") : null;
            JSONArray avg30 = stats != null ? stats.optJSONArray("avg30") : null;

            int monthlySold = product.optInt("monthlySold", 0);
            JSONArray buyBoxSellerIdHistory = product.optJSONArray("buyBoxSellerIdHistory");
            JSONObject buyBoxStats = stats != null ? stats.optJSONObject("buyBoxStats") : null;
            int winnerCount30 = buyBoxStats != null ? buyBoxStats.length() : 0;

            JSONArray buyBoxEligibleOfferCounts = product.optJSONArray("buyBoxEligibleOfferCounts");

            int stockAmazon = stats.optInt("stockAmazon", 0);
            double pickAndPackFee = fbaFees != null ? fbaFees.optDouble("pickAndPackFee", 0) : 0;
            double referralFeePercentage = product.optDouble("referralFeePercentage", 0.0);
            int buyBoxPrice = stats.optInt("buyBoxPrice", 0);
            double referralFeeBuyBox = buyBoxPrice * (referralFeePercentage / 100);

            JSONArray eanList = product.optJSONArray("eanList");
            String asin = product.optString("asin");
            String brand = product.optString("brand");
            String type = product.optString("type", "N/A");

            // Prepare the seller name lookup asynchronously
            CompletableFuture<String> sellerNameFuture = CompletableFuture.completedFuture("N/A");
            if (buyBoxSellerIdHistory != null && buyBoxSellerIdHistory.length() > 0) {
                String lastSellerId = buyBoxSellerIdHistory.getString(buyBoxSellerIdHistory.length() - 1);
                sellerNameFuture = fetchSellerName(lastSellerId, locale);
            }

            // Collect all fields and save them as variables
            String sellerName = sellerNameFuture.join();  // wait for the seller name asynchronously
            double salesCurrent = current != null && current.length() > 3 ? current.getDouble(3) : 0.00;
            double salesAvg30 = avg30 != null && avg30.length() > 3 ? avg30.getDouble(3) : 0.00;
            double buyBoxShippingCurrent = current != null && current.length() > 18 ? current.getDouble(18) / 100 : 0.00;
            double buyBoxShippingAvg30 = avg30 != null && avg30.length() > 18 ? avg30.getDouble(18) / 100 : 0.00;
            int buyBoxEligibleOfferCount = buyBoxEligibleOfferCounts != null && buyBoxEligibleOfferCounts.length() > 0 ? buyBoxEligibleOfferCounts.getInt(0) : 0;
            double newPriceCurrent = current != null && current.length() > 1 ? current.getDouble(1) / 100 : 0.00;
            double newPriceAvg30 = avg30 != null && avg30.length() > 1 ? avg30.getDouble(1) / 100 : 0.00;

            // Insert data into the PostgreSQL database
            insertProductDataIntoDatabase(locale, title, salesCurrent, salesAvg30, monthlySold, buyBoxShippingCurrent, buyBoxShippingAvg30, sellerName, winnerCount30, winnerCount90, buyBoxEligibleOfferCount, stockAmazon, newPriceCurrent, newPriceAvg30, pickAndPackFee, referralFeePercentage, referralFeeBuyBox, asin, formatEanList(eanList), type, brand);

        } catch (Exception e) {
            println("Error processing product data: " + e.getMessage());
        }
    }

    // Method to fetch seller name asynchronously
    public static CompletableFuture<String> fetchSellerName(String lastSellerId, AmazonLocale locale) {
        // If no seller ID is provided, return "N/A" immediately
        if (lastSellerId == null || lastSellerId.isEmpty()) {
            return CompletableFuture.completedFuture("N/A");
        }

        // Create request for seller information
        Request sellerInfoRequest = Request.getSellerRequest(locale, lastSellerId);

        // Retrieve seller information asynchronously
        return CompletableFuture.supplyAsync(() -> {
            try {
                CompletableFuture<String> resultFuture = new CompletableFuture<>();
                KeepaAPI api = new KeepaAPI(Dotenv.load().get("API_KEY"));
                api.sendRequest(sellerInfoRequest)
                    .done(sellerResult -> {
                        try {
                            // Parse the JSON response
                            JSONObject sellerJson = new JSONObject(sellerResult.toString());

                            // Access the sellers object
                            JSONObject sellers = sellerJson.optJSONObject("sellers");

                            // Ensure that sellers object is not null and has at least one seller
                            if (sellers != null && sellers.length() > 0) {
                                // Get the first seller entry (assuming you want the first one)
                                String firstSellerKey = sellers.keys().next();
                                JSONObject firstSeller = sellers.getJSONObject(firstSellerKey);

                                // Extract sellerName
                                String sellerName = firstSeller.optString("sellerName", "N/A");
                                resultFuture.complete(sellerName);
                            } else {
                                resultFuture.complete("N/A");
                            }
                        } catch (JSONException e) {
                            resultFuture.completeExceptionally(new RuntimeException("Error parsing seller info: " + e.getMessage()));
                        }
                    })
                    .fail(failure -> resultFuture.completeExceptionally(new RuntimeException("Error fetching seller info: " + failure)));
                return resultFuture.join();
            } catch (Exception e) {
                return "N/A";
            }
        });
    }

    // Method to insert the processed product data into the PostgreSQL database
    private static void insertProductDataIntoDatabase(AmazonLocale locale, String title, double salesCurrent, double salesAvg30, int monthlySold, double buyBoxShippingCurrent, double buyBoxShippingAvg30, String sellerName, int winnerCount30, int winnerCount90, int buyBoxEligibleOfferCount, int stockAmazon, double newPriceCurrent, double newPriceAvg30, double pickAndPackFee, double referralFeePercentage, double referralFeeBuyBox, String asin, String eanList, String type, String brand) {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
    
        try {
            // Load environment variables
            Dotenv dotenv = Dotenv.load();
            String dbUrl = dotenv.get("DB_URL");
            String dbUser = dotenv.get("DB_USER");
            String dbPassword = dotenv.get("DB_PASSWORD");
            String schemaName = dotenv.get("DB_SCHEMA");
    
            // Establish the database connection
            connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
    
            // Set the schema for this session
            setSchemaForSession(connection, schemaName);
    
            // Determine the table name based on the locale
            String tableName = "products_" + locale.toString().toLowerCase();
    
            // Create the table if it doesn't exist
            createTableIfNotExists(connection, tableName);
    
            // Prepare the SQL UPSERT statement
            String sql = "INSERT INTO " + tableName + " (title, sales_current, sales_avg30, monthly_sold, buy_box_shipping_current, buy_box_shipping_avg30, seller_name, winner_count_30, winner_count_90, buy_box_eligible_offer_count, stock_amazon, new_price_current, new_price_avg30, fba_fees, referral_fee_percentage, referral_buybox_fee, asin, ean_list, type, brand, last_updated) "
                       + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                       + "ON CONFLICT (asin) DO UPDATE "
                       + "SET title = EXCLUDED.title, sales_current = EXCLUDED.sales_current, sales_avg30 = EXCLUDED.sales_avg30, monthly_sold = EXCLUDED.monthly_sold, "
                       + "buy_box_shipping_current = EXCLUDED.buy_box_shipping_current, buy_box_shipping_avg30 = EXCLUDED.buy_box_shipping_avg30, seller_name = EXCLUDED.seller_name, "
                       + "winner_count_30 = EXCLUDED.winner_count_30, winner_count_90 = EXCLUDED.winner_count_90, buy_box_eligible_offer_count = EXCLUDED.buy_box_eligible_offer_count, "
                       + "stock_amazon = EXCLUDED.stock_amazon, new_price_current = EXCLUDED.new_price_current, new_price_avg30 = EXCLUDED.new_price_avg30, fba_fees = EXCLUDED.fba_fees, "
                       + "referral_fee_percentage = EXCLUDED.referral_fee_percentage, referral_buybox_fee = EXCLUDED.referral_buybox_fee, ean_list = EXCLUDED.ean_list, type = EXCLUDED.type, "
                       + "brand = EXCLUDED.brand, last_updated = NOW()";
    
            preparedStatement = connection.prepareStatement(sql);
    
            // Set the values for the PreparedStatement
            preparedStatement.setString(1, title);
            preparedStatement.setDouble(2, salesCurrent);
            preparedStatement.setDouble(3, salesAvg30);
            preparedStatement.setInt(4, monthlySold);
            preparedStatement.setDouble(5, buyBoxShippingCurrent);
            preparedStatement.setDouble(6, buyBoxShippingAvg30);
            preparedStatement.setString(7, sellerName);
            preparedStatement.setInt(8, winnerCount30);
            preparedStatement.setInt(9, winnerCount90);
            preparedStatement.setInt(10, buyBoxEligibleOfferCount);
            if (stockAmazon == -2) {
                preparedStatement.setNull(11, java.sql.Types.INTEGER);
            } else {
                preparedStatement.setInt(11, stockAmazon);
            }
            preparedStatement.setDouble(12, newPriceCurrent);
            preparedStatement.setDouble(13, newPriceAvg30);
            preparedStatement.setDouble(14, pickAndPackFee / 100);  // Convert cents to Euros
            preparedStatement.setDouble(15, referralFeePercentage);
            preparedStatement.setDouble(16, Math.round((referralFeeBuyBox / 100) * 100.0) / 100.0); // Convert cents to Euros
            preparedStatement.setString(17, asin);
            preparedStatement.setString(18, eanList);
            preparedStatement.setString(19, type);
            preparedStatement.setString(20, brand);
            preparedStatement.setTimestamp(21, new java.sql.Timestamp(System.currentTimeMillis()));  // Add current timestamp for last_updated field
    
            // Execute the upsert operation
            preparedStatement.executeUpdate();
    
            println("Product data upserted successfully into table: " + tableName);
    
        } catch (SQLException e) {
            println("Error inserting product data into database: " + e.getMessage());
        } finally {
            // Close the resources
            if (preparedStatement != null) {
                try {
                    preparedStatement.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void createTableIfNotExists(Connection connection, String tableName) throws SQLException {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
            + "title TEXT, "
            + "sales_current DOUBLE PRECISION, "
            + "sales_avg30 DOUBLE PRECISION, "
            + "monthly_sold INT, "
            + "buy_box_shipping_current DOUBLE PRECISION, "
            + "buy_box_shipping_avg30 DOUBLE PRECISION, "
            + "seller_name TEXT, "
            + "winner_count_30 INT, "
            + "winner_count_90 INT, "
            + "buy_box_eligible_offer_count INT, "
            + "stock_amazon INT, "
            + "new_price_current DOUBLE PRECISION, "
            + "new_price_avg30 DOUBLE PRECISION, "
            + "fba_fees DOUBLE PRECISION, "
            + "referral_fee_percentage DOUBLE PRECISION, "
            + "referral_buybox_fee DOUBLE PRECISION, "
            + "asin VARCHAR(20) PRIMARY KEY, "
            + "ean_list TEXT, "
            + "type TEXT, "
            + "brand TEXT, "
            + "last_updated TIMESTAMP"
            + ");";
    
        try (PreparedStatement preparedStatement = connection.prepareStatement(createTableSQL)) {
            preparedStatement.execute();
        }
    }
       
    // Method to format EAN list as a single string with spaces
    private static String formatEanList(JSONArray eanList) {
        StringBuilder eanStringBuilder = new StringBuilder();
        if (eanList != null && eanList.length() > 0) {
            for (int i = 0; i < eanList.length(); i++) {
                eanStringBuilder.append(eanList.getString(i));
                if (i < eanList.length() - 1) {
                    eanStringBuilder.append(", ");
                }
            }
        }
        return eanStringBuilder.toString();
    }

    private static void deleteOldData(AmazonLocale locale) {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
    
        try {
            // Load environment variables
            Dotenv dotenv = Dotenv.load();
            String dbUrl = dotenv.get("DB_URL");
            String dbUser = dotenv.get("DB_USER");
            String dbPassword = dotenv.get("DB_PASSWORD");
            String schemaName = dotenv.get("DB_SCHEMA");
    
            // Establish the database connection
            connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
    
            // Set the schema for this session
            setSchemaForSession(connection, schemaName);
    
            // Determine the table name based on the locale
            String tableName = "products_" + locale.toString().toLowerCase();
    
            // Prepare the SQL DELETE statement
            String sql = "DELETE FROM " + tableName + " WHERE last_updated < NOW() - INTERVAL '30 days'";
    
            preparedStatement = connection.prepareStatement(sql);
    
            // Execute the delete operation
            int rowsDeleted = preparedStatement.executeUpdate();
            println("Deleted " + rowsDeleted + " old records from table: " + tableName);
    
        } catch (SQLException e) {
            println("Error deleting old data from database: " + e.getMessage());
        } finally {
            // Close the resources
            if (preparedStatement != null) {
                try {
                    preparedStatement.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void setSchemaForSession(Connection connection, String schemaName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Set the schema for the session
            String sql = "SET search_path TO " + schemaName;
            statement.execute(sql);
        }
    }
}
