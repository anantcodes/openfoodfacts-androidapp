package openfoodfacts.github.scrachx.openfood.network;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.afollestad.materialdialogs.MaterialDialog;
import com.fasterxml.jackson.databind.JsonNode;

import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.SingleObserver;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import openfoodfacts.github.scrachx.openfood.AppFlavors;
import openfoodfacts.github.scrachx.openfood.BuildConfig;
import openfoodfacts.github.scrachx.openfood.R;
import openfoodfacts.github.scrachx.openfood.images.ImageKeyHelper;
import openfoodfacts.github.scrachx.openfood.images.ProductImage;
import openfoodfacts.github.scrachx.openfood.models.HistoryProduct;
import openfoodfacts.github.scrachx.openfood.models.HistoryProductDao;
import openfoodfacts.github.scrachx.openfood.models.OfflineSavedProduct;
import openfoodfacts.github.scrachx.openfood.models.Product;
import openfoodfacts.github.scrachx.openfood.models.ProductImageField;
import openfoodfacts.github.scrachx.openfood.models.ProductIngredient;
import openfoodfacts.github.scrachx.openfood.models.Search;
import openfoodfacts.github.scrachx.openfood.models.SendProduct;
import openfoodfacts.github.scrachx.openfood.models.State;
import openfoodfacts.github.scrachx.openfood.models.ToUploadProduct;
import openfoodfacts.github.scrachx.openfood.models.ToUploadProductDao;
import openfoodfacts.github.scrachx.openfood.network.services.ProductsAPI;
import openfoodfacts.github.scrachx.openfood.utils.ImageUploadListener;
import openfoodfacts.github.scrachx.openfood.utils.LocaleHelper;
import openfoodfacts.github.scrachx.openfood.utils.Utils;
import openfoodfacts.github.scrachx.openfood.views.AddProductActivity;
import openfoodfacts.github.scrachx.openfood.views.InstallationUtils;
import openfoodfacts.github.scrachx.openfood.views.OFFApplication;
import openfoodfacts.github.scrachx.openfood.views.product.ProductActivity;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

import static openfoodfacts.github.scrachx.openfood.models.ProductImageField.FRONT;
import static openfoodfacts.github.scrachx.openfood.models.ProductImageField.INGREDIENTS;
import static openfoodfacts.github.scrachx.openfood.models.ProductImageField.NUTRITION;

/**
 * API Client for all API callbacks
 */
public class OpenFoodAPIClient {
    public static final String MIME_TEXT = "text/plain";
    public static final String PNG_EXT = ".png\"";
    private Disposable historySyncDisp;
    private HistoryProductDao mHistoryProductDao;
    private ToUploadProductDao mToUploadProductDao;
    @NonNull
    private final ProductsAPI api;
    @NonNull
    private final Context mContext;
    private static final String FIELDS_TO_FETCH_FACETS = String
        .format("brands,%s,product_name,image_small_url,quantity,nutrition_grades_tags,code", getLocaleProductNameField());

    public OpenFoodAPIClient(@NonNull Context context) {
        this(context, null);
    }

    /**
     * Returns API Service for OpenFoodAPIClient
     *
     * @param customApiUrl base url for the API
     */
    public OpenFoodAPIClient(@NonNull Context context, @Nullable String customApiUrl) {
        if (customApiUrl != null) {
            api = new Retrofit.Builder()
                .baseUrl(customApiUrl)
                .client(Utils.httpClientBuilder())
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(JacksonConverterFactory.create())
                .addCallAdapterFactory(RxJava2CallAdapterFactory.createWithScheduler(Schedulers.io()))
                .build()
                .create(ProductsAPI.class);
        } else {
            api = CommonApiManager.getInstance().getProductsApi();
        }
        mHistoryProductDao = Utils.getDaoSession().getHistoryProductDao();
        mToUploadProductDao = Utils.getDaoSession().getToUploadProductDao();
        mContext = context;
    }

    /**
     * Uploads comment by users
     *
     * @param login the username
     */
    public static String getCommentToUpload(String login) {
        StringBuilder comment;
        switch (BuildConfig.FLAVOR) {
            case AppFlavors.OBF:
                comment = new StringBuilder("Official Open Beauty Facts Android app");
                break;
            case AppFlavors.OPFF:
                comment = new StringBuilder("Official Open Pet Food Facts Android app");
                break;
            case AppFlavors.OPF:
                comment = new StringBuilder("Official Open Products Facts Android app");
                break;
            case AppFlavors.OFF:
            default:
                comment = new StringBuilder("Official Open Food Facts Android app");
                break;
        }

        final OFFApplication instance = OFFApplication.getInstance();
        comment.append(" ").append(Utils.getVersionName(instance));
        if (login.isEmpty()) {
            comment.append(" (Added by ").append(InstallationUtils.id(instance)).append(")");
        }
        return comment.toString();
    }

    public static String getCommentToUpload() {
        return getCommentToUpload("");
    }

    public static String getLocaleProductNameField() {
        String locale = LocaleHelper.getLanguage(OFFApplication.getInstance());
        return "product_name_" + locale;
    }

    /**
     * Add a product to ScanHistory synchronously
     *
     * @param mHistoryProductDao
     * @param product
     */
    public static void addToHistorySync(HistoryProductDao mHistoryProductDao, Product product) {
        List<HistoryProduct> historyProducts = mHistoryProductDao.queryBuilder().where(HistoryProductDao.Properties.Barcode.eq(product.getCode())).list();
        HistoryProduct hp = new HistoryProduct(product.getProductName(),
            product.getBrands(),
            product.getImageSmallUrl(LocaleHelper.getLanguage(OFFApplication.getInstance())),
            product.getCode(),
            product.getQuantity(),
            product.getNutritionGradeFr());
        if (!historyProducts.isEmpty()) {
            hp.setId(historyProducts.get(0).getId());
        }
        mHistoryProductDao.insertOrReplace(hp);
    }

    public static void addToHistorySync(HistoryProductDao mHistoryProductDao, OfflineSavedProduct offlineSavedProduct) {
        List<HistoryProduct> historyProducts = mHistoryProductDao.queryBuilder().where(HistoryProductDao.Properties.Barcode.eq(offlineSavedProduct.getBarcode())).list();
        HashMap<String, String> map = offlineSavedProduct.getProductDetailsMap();

        HistoryProduct hp = new HistoryProduct(offlineSavedProduct.getName(),
            map.get(ApiFields.Keys.ADD_BRANDS),
            offlineSavedProduct.getImageFrontLocalUrl(),
            offlineSavedProduct.getBarcode(),
            map.get(ApiFields.Keys.QUANTITY),
            null);
        if (!historyProducts.isEmpty()) {
            hp.setId(historyProducts.get(0).getId());
        }
        mHistoryProductDao.insertOrReplace(hp);
    }

    public Single<State> getProductStateFull(final String barcode, String header) {
        return api.getProductByBarcodeSingle(barcode, getAllFields(), Utils.getUserAgent(header))
            .subscribeOn(Schedulers.io());
    }

    public Single<State> getProductStateFull(final String barcode) {
        return api.getProductByBarcodeSingle(barcode, getAllFields(), Utils.getUserAgent(Utils.HEADER_USER_AGENT_SEARCH))
            .subscribeOn(Schedulers.io());
    }

    private String getAllFields() {
        String[] allFieldsArray = OFFApplication.getInstance().getResources().getStringArray(R.array.product_all_fields_array);
        Set<String> fields = new HashSet<>(Arrays.asList(allFieldsArray));
        String langCode = LocaleHelper.getLanguage(OFFApplication.getInstance().getApplicationContext());
        String[] fieldsToLocalizedArray = OFFApplication.getInstance().getResources().getStringArray(R.array.fields_array);
        for (String fieldToLocalize : fieldsToLocalizedArray) {
            fields.add(fieldToLocalize + "_" + langCode);
            fields.add(fieldToLocalize + "_en");
        }
        return StringUtils.join(fields, ',');
    }

    public MaterialDialog.Builder productNotFoundDialogBuilder(Activity activity, String barcode) {
        return new MaterialDialog.Builder(activity)
            .title(R.string.txtDialogsTitle)
            .content(R.string.txtDialogsContent)
            .positiveText(R.string.txtYes)
            .negativeText(R.string.txtNo)
            .onPositive((dialog, which) -> {
                if (!activity.isFinishing()) {
                    Intent intent = new Intent(activity, AddProductActivity.class);
                    State st = new State();
                    Product pd = new Product();
                    pd.setCode(barcode);
                    st.setProduct(pd);
                    intent.putExtra("state", st);
                    activity.startActivity(intent);
                    activity.finish();
                }
            });
    }

    /**
     * Open the product activity if the barcode exist.
     * Also add it in the history if the product exist.
     *
     * @param barcode product barcode
     */
    public void getProductImages(final String barcode, final ApiCallbacks.OnStateListenerCallback callback) {
        String[] allFieldsArray = OFFApplication.getInstance().getResources().getStringArray(R.array.product_images_fields_array);
        Set<String> fields = new HashSet<>(Arrays.asList(allFieldsArray));
        String langCode = LocaleHelper.getLanguage(OFFApplication.getInstance().getApplicationContext());
        fields.add("product_name_" + langCode);
        api.getProductByBarcode(barcode, StringUtils.join(fields, ','), Utils.getUserAgent(Utils.HEADER_USER_AGENT_SEARCH)).enqueue(new Callback<State>() {
            @Override
            public void onResponse(@NonNull Call<State> call, @NonNull Response<State> response) {
                callback.onStateResponse(response.body());
            }

            @Override
            public void onFailure(@NonNull Call<State> call, @NonNull Throwable t) {
                boolean isNetwork = (t instanceof IOException);
                if (callback != null) {
                    State res = new State();
                    res.setStatus(0);
                    res.setStatusVerbose(isNetwork ? OFFApplication.getInstance().getResources().getString(R.string.errorWeb) : t.getMessage());
                    callback.onStateResponse(res);
                }
            }
        });
    }

    // TODO: Move it to utility class
    public static String fillWithUserLoginInfo(Map<String, RequestBody> imgMap) {
        Map<String, String> values = addUserInfo(new HashMap<>());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            imgMap.put(entry.getKey(), RequestBody.create(MediaType.parse(MIME_TEXT), entry.getValue()));
        }
        return values.get(ApiFields.Keys.USER_ID);
    }

    /**
     * Open the product activity if the barcode exist.
     * Also add it in the history if the product exist.
     *
     * @param barcode product barcode
     * @param activity
     */
    // TODO: This is not part of the client, move it to another class (preferably a utility class)
    public void openProduct(final String barcode, final Activity activity, final ApiCallbacks.OnStateListenerCallback callback) {
        String fieldParam = getAllFields();
        api.getProductByBarcode(barcode, fieldParam, Utils.getUserAgent(Utils.HEADER_USER_AGENT_SEARCH)).enqueue(new Callback<State>() {
            @Override
            public void onResponse(@NonNull Call<State> call, @NonNull Response<State> response) {
                if (activity == null && callback == null) {
                    return;
                }
                if (activity != null && activity.isFinishing()) {
                    return;
                }

                final State s = response.body();
                if (s == null) {
                    Toast.makeText(activity, R.string.something_went_wrong, Toast.LENGTH_LONG).show();
                    return;
                }
                if (s.getStatus() == 0) {
                    if (activity != null) {
                        productNotFoundDialogBuilder(activity, barcode)
                            .onNegative((dialog, which) -> activity.onBackPressed())
                            .show();
                    }
                } else {
                    if (activity != null) {
                        addToHistory(s.getProduct()).subscribe();
                    }
                    Bundle bundle = new Bundle();

                    s.setProduct(s.getProduct());
                    if (callback != null) {
                        callback.onStateResponse(s);
                    } else {
                        Intent intent = new Intent(activity, ProductActivity.class);
                        bundle.putSerializable("state", s);
                        intent.putExtras(bundle);
                        activity.startActivity(intent);
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call<State> call, @NonNull Throwable t) {

                if (activity == null || activity.isFinishing()) {
                    return;
                }
                boolean isNetwork = (t instanceof IOException);
                if (callback != null) {
                    State res = new State();
                    res.setStatus(0);
                    res.setStatusVerbose(isNetwork ? activity.getResources().getString(R.string.errorWeb) : t.getMessage());
                    callback.onStateResponse(res);
                }
                if (!isNetwork) {
                    productNotFoundDialogBuilder(activity, barcode).show();
                }
            }
        });
    }

    /**
     * Open the product in {@link ProductActivity} if the barcode exist.
     * Also add it in the history if the product exist.
     *
     * @param barcode product barcode
     * @param activity
     */
    public void openProduct(final String barcode, final Activity activity) {
        openProduct(barcode, activity, null);
    }

    /**
     * @param barcode
     * @return a single containing a list of product ingredients (can be empty)
     */
    public Single<List<ProductIngredient>> getIngredients(String barcode) {
        return api.getIngredientsByBarcode(barcode).map(node -> {
            if (node == null) {
                return Collections.emptyList();
            }
            final JsonNode ingredientsJsonNode = node.findValue("ingredients");
            if (ingredientsJsonNode == null) {
                return Collections.emptyList();
            }
            ArrayList<ProductIngredient> productIngredients = new ArrayList<>();
            final int nbIngredient = ingredientsJsonNode.size();

            // add ingredients to list from json
            for (int i = 0; i < nbIngredient; i++) {
                ProductIngredient productIngredient = new ProductIngredient();
                final JsonNode ingredient = ingredientsJsonNode.get(i);
                if (ingredient != null) {
                    productIngredient.setId(ingredient.findValue("id").toString());
                    productIngredient.setText(ingredient.findValue("text").toString());
                    final JsonNode rankNode = ingredient.findValue("rank");
                    if (rankNode == null) {
                        productIngredient.setRank(-1);
                    } else {
                        productIngredient.setRank(Long.parseLong(rankNode.toString()));
                    }
                    productIngredients.add(productIngredient);
                }
            }
            return productIngredients;
        });
    }

    public void onResponseCallForPostFunction(Call<State> call,
                                              Response<State> response,
                                              Context activity,
                                              final ApiCallbacks.OnProductSentCallback productSentCallback,
                                              SendProduct product) {
        postImages(response, activity, productSentCallback, product);
    }

    /**
     * @return This api service gets products of provided brand.
     */
    public ProductsAPI getRawAPI() {
        return api;
    }

    /**
     * Returns images for the current product
     *
     * @param barcode barcode for the current product
     * @param onImagesCallback reference to the OnImagesCallback interface
     */
    public void getImages(String barcode, ApiCallbacks.OnImagesCallback onImagesCallback) {

        api.getProductImages(barcode).enqueue(new Callback<String>() {
            @Override
            public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                onImagesCallback.onImageResponse(true, response.body());
            }

            @Override
            public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                onImagesCallback.onImageResponse(false, null);
            }
        });
    }

    public void searchProductsByName(final String name, final int page, final Activity activity, final ApiCallbacks.OnProductsCallback productsCallback) {
        String productNameLocale = getLocaleProductNameField();
        String fields = "selected_images,image_small_url,product_name,brands,quantity,code,nutrition_grade_fr," + productNameLocale;

        api.searchProductByName(fields, name, page).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (!response.isSuccessful()) {
                    productsCallback.onProductsResponse(false, null, -1);
                    return;
                }

                Search s = response.body();
                if (s == null || Integer.parseInt(s.getCount()) == 0) {
                    productsCallback.onProductsResponse(false, null, -2);
                } else {
                    productsCallback.onProductsResponse(true, s, Integer.parseInt(s.getCount()));
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                if (activity != null && !activity.isFinishing()) {
                    Toast.makeText(activity, activity.getString(R.string.errorWeb), Toast.LENGTH_LONG).show();
                }
                productsCallback.onProductsResponse(false, null, -1);
            }
        });
    }

    public void postImg(final ProductImage image, @Nullable ImageUploadListener imageUploadListener) {
        postImg(image, false, imageUploadListener);
    }

    /**
     * Returns a map for images uploaded for product/ingredients/nutrition/other images
     *
     * @param image object of ProductImage
     */
    private Map<String, RequestBody> getUploadableMap(ProductImage image) {
        final String lang = image.getLanguage();

        Map<String, RequestBody> imgMap = new HashMap<>();
        imgMap.put("code", image.getCode());
        imgMap.put("imagefield", image.getField());
        if (image.getImguploadFront() != null) {
            imgMap.put("imgupload_front\"; filename=\"front_" + lang + PNG_EXT, image.getImguploadFront());
        }
        if (image.getImguploadIngredients() != null) {
            imgMap.put("imgupload_ingredients\"; filename=\"ingredients_" + lang + PNG_EXT, image.getImguploadIngredients());
        }
        if (image.getImguploadNutrition() != null) {
            imgMap.put("imgupload_nutrition\"; filename=\"nutrition_" + lang + PNG_EXT, image.getImguploadNutrition());
        }
        if (image.getImguploadOther() != null) {
            imgMap.put("imgupload_other\"; filename=\"other_" + lang + PNG_EXT, image.getImguploadOther());
        }

        // Attribute the upload to the connected user
        fillWithUserLoginInfo(imgMap);
        return imgMap;
    }

    public void getProductsByCountry(String country, final int page, final ApiCallbacks.OnCountryCallback onCountryCallback) {
        api.getProductsByCountry(country, page, FIELDS_TO_FETCH_FACETS).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onCountryCallback.onCountryResponse(true, response.body());
                } else {
                    onCountryCallback.onCountryResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {

                onCountryCallback.onCountryResponse(false, null);
            }
        });
    }

    public void getProductsByLabel(String label, final int page, final ApiCallbacks.OnLabelCallback onLabelCallback) {
        api.getProductByLabel(label, page, FIELDS_TO_FETCH_FACETS).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onLabelCallback.onLabelResponse(true, response.body());
                } else {
                    onLabelCallback.onLabelResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onLabelCallback.onLabelResponse(false, null);
            }
        });
    }

    public void getProductsByCategory(String category, final int page, final ApiCallbacks.OnCategoryCallback onCategoryCallback) {
        api.getProductByCategory(category, page).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onCategoryCallback.onCategoryResponse(true, response.body());
                } else {
                    onCategoryCallback.onCategoryResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onCategoryCallback.onCategoryResponse(false, null);
            }
        });
    }

    public void getProductsByContributor(String contributor, final int page, final ApiCallbacks.OnContributorCallback onContributorCallback) {
        api.searchProductsByContributor(contributor, page).enqueue(createCallback(onContributorCallback));
    }

    /**
     * Add a product to ScanHistory asynchronously
     */
    public Completable addToHistory(Product product) {
        return Completable.fromAction(() -> addToHistorySync(mHistoryProductDao, product));
    }

    public void getProductsByPackaging(final String packaging, final int page, final ApiCallbacks.OnPackagingCallback onPackagingCallback) {

        api.getProductByPackaging(packaging, page, FIELDS_TO_FETCH_FACETS).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onPackagingCallback.onPackagingResponse(true, response.body());
                } else {
                    onPackagingCallback.onPackagingResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onPackagingCallback.onPackagingResponse(false, null);
            }
        });
    }

    /**
     * upload images in offline mode
     *
     * @param context context
     * @return ListenableFuture
     */
    public Completable uploadOfflineImages(Context context) {
        return Single.fromCallable(() -> {
            List<ToUploadProduct> toUploadProductList = mToUploadProductDao.queryBuilder()
                .where(ToUploadProductDao.Properties.Uploaded.eq(false))
                .list();

            int totalSize = toUploadProductList.size();
            List<Completable> completables = new ArrayList<>();
            for (int i = 0; i < totalSize; i++) {
                ToUploadProduct uploadProduct = toUploadProductList.get(i);
                File imageFile;
                try {
                    imageFile = new File(uploadProduct.getImageFilePath());
                } catch (Exception e) {
                    Log.e("OfflineUploadingTask", "doInBackground", e);
                    continue;
                }
                ProductImage productImage = new ProductImage(uploadProduct.getBarcode(),
                    uploadProduct.getProductField(), imageFile);
                completables.add(api.saveImageSingle(OpenFoodAPIClient.this.getUploadableMap(productImage))
                    .flatMapCompletable((Function<JsonNode, Completable>) jsonNode -> {
                        if (jsonNode != null) {
                            Log.d("onResponse", jsonNode.toString());
                            if (!jsonNode.isObject()) {
                                return Completable.error(new IOException("jsonNode is not an object"));
                            } else if (jsonNode.get("status").asText().contains("status not ok")) {
                                mToUploadProductDao.delete(uploadProduct);
                                return Completable.error(new IOException("status not ok"));
                            } else {
                                mToUploadProductDao.delete(uploadProduct);
                                return Completable.complete();
                            }
                        } else {
                            return Completable.error(new IOException("jsonNode is null"));
                        }
                    }));
            }
            return completables;
        }).flatMapCompletable(Completable::merge);
    }

    public void getProductsByStore(final String store, final int page, final ApiCallbacks.OnStoreCallback onStoreCallback) {
        api.getProductByStores(store, page, FIELDS_TO_FETCH_FACETS).enqueue(createStoreCallback(onStoreCallback));
    }

    /**
     * Search for products using bran name
     *
     * @param brand search query for product
     * @param page page numbers
     */
    public Single<Search> getProductsByBrandSingle(final String brand, final int page) {
        return api.getProductByBrandsSingle(brand, page, FIELDS_TO_FETCH_FACETS);
    }

    public static Map<String, String> addUserInfo(Map<String, String> imgMap) {
        final SharedPreferences settings = OFFApplication.getInstance().getSharedPreferences("login", 0);
        final String login = settings.getString("user", "");
        imgMap.put("comment", OpenFoodAPIClient.getCommentToUpload(login));
        if (StringUtils.isNotBlank(login)) {
            imgMap.put(ApiFields.Keys.USER_ID, login);
        }
        final String password = settings.getString("pass", "");
        if (StringUtils.isNotBlank(password)) {
            imgMap.put("password", password);
        }
        return imgMap;
    }

    public void postImg(final ProductImage image, boolean setAsDefault, @Nullable ImageUploadListener imageUploadListener) {
        api.saveImage(getUploadableMap(image))
            .enqueue(new Callback<JsonNode>() {
                @Override
                public void onResponse(@NonNull Call<JsonNode> call, @NonNull Response<JsonNode> response) {
                    Log.d("onResponse", response.toString());
                    if (!response.isSuccessful()) {
                        ToUploadProduct product = new ToUploadProduct(image.getBarcode(), image.getFilePath(), image.getImageField().toString());
                        mToUploadProductDao.insertOrReplace(product);
                        Toast.makeText(OpenFoodAPIClient.this.mContext, response.toString(), Toast.LENGTH_LONG).show();
                        if (imageUploadListener != null) {
                            imageUploadListener.onFailure(response.toString());
                        }
                        return;
                    }

                    JsonNode body = response.body();
                    if (body.isObject()) {
                        if (body.get("status").asText().contains("status not ok")) {
                            Toast.makeText(OpenFoodAPIClient.this.mContext, body.get("error").asText(), Toast.LENGTH_LONG).show();
                            if (imageUploadListener != null) {
                                imageUploadListener.onFailure(body.get("error").asText());
                            }
                        } else {
                            if (setAsDefault) {
                                setAsDefaultImage(body);
                            } else if (imageUploadListener != null) {
                                imageUploadListener.onSuccess();
                            }
                        }
                    }
                }

                private void setAsDefaultImage(JsonNode body) {
                    Map<String, String> queryMap = new HashMap<>();
                    queryMap.put("imgid", body.get("image").get("imgid").asText());
                    queryMap.put("id", body.get("imagefield").asText());
                    addUserInfo(queryMap);
                    api.editImageSingle(image.getBarcode(), queryMap)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new SingleObserver<JsonNode>() {
                            @Override
                            public void onSubscribe(Disposable d) {

                            }

                            @Override
                            public void onSuccess(JsonNode jsonNode) {
                                if ("status ok".equals(jsonNode.get("status").asText())
                                    && imageUploadListener != null) {
                                    imageUploadListener.onSuccess();
                                }
                            }

                            @Override
                            public void onError(Throwable e) {
                                Log.i(this.getClass().getSimpleName(), e.getMessage());
                                if (imageUploadListener != null) {
                                    imageUploadListener.onFailure(e.getMessage());
                                }
                            }
                        });
                }

                @Override
                public void onFailure(@NonNull Call<JsonNode> call, @NonNull Throwable t) {
                    Log.d("onResponse", t.toString());
                    if (imageUploadListener != null) {
                        imageUploadListener.onFailure(OpenFoodAPIClient.this.mContext.getString(R.string.uploadLater));
                    }
                    ToUploadProduct product = new ToUploadProduct(image.getBarcode(), image.getFilePath(), image.getImageField().toString());
                    mToUploadProductDao.insertOrReplace(product);
                    Toast.makeText(OpenFoodAPIClient.this.mContext, OpenFoodAPIClient.this.mContext.getString(R.string.uploadLater), Toast.LENGTH_LONG).show();
                }
            });
    }

    public void editImage(String code, Map<String, String> imgMap, ApiCallbacks.OnEditImageCallback onEditImageCallback) {
        addUserInfo(imgMap);
        api.editImages(code, imgMap).enqueue(createCallback(onEditImageCallback));
    }

    /**
     * Unselect the image from the product code.
     *
     * @param code code of the product
     * @param onEditImageCallback
     */
    public void unSelectImage(String code, ProductImageField field, String language, ApiCallbacks.OnEditImageCallback onEditImageCallback) {
        Map<String, String> imgMap = new HashMap<>();
        addUserInfo(imgMap);
        imgMap.put(ImageKeyHelper.IMAGE_STRING_ID, ImageKeyHelper.getImageStringKey(field, language));
        api.unselectImage(code, imgMap).enqueue(createCallback(onEditImageCallback));
    }

    private Callback<String> createCallback(ApiCallbacks.OnEditImageCallback onEditImageCallback) {
        return new Callback<String>() {
            @Override
            public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                onEditImageCallback.onEditResponse(true, response.body());
            }

            @Override
            public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                onEditImageCallback.onEditResponse(false, null);
            }
        };
    }

    public void getProductsByOrigin(final String origin, final int page, final ApiCallbacks.OnStoreCallback onStoreCallback) {
        api.getProductsByOrigin(origin, page, FIELDS_TO_FETCH_FACETS).enqueue(createStoreCallback(onStoreCallback));
    }

    public void syncOldHistory() {
        if (historySyncDisp != null) {
            historySyncDisp.dispose();
        }
        historySyncDisp = Completable.fromAction(() -> {
            List<HistoryProduct> historyProducts = mHistoryProductDao.loadAll();
            int size = historyProducts.size();
            for (int i = 0; i < size; i++) {
                HistoryProduct historyProduct = historyProducts.get(i);
                api.getShortProductByBarcode(historyProduct.getBarcode(), Utils.getUserAgent(Utils.HEADER_USER_AGENT_SEARCH)).enqueue(new Callback<State>() {
                    @Override
                    public void onResponse(@NonNull Call<State> call, @NonNull Response<State> response) {
                        final State s = response.body();

                        if (s != null && s.getStatus() != 0) {
                            Product product = s.getProduct();
                            HistoryProduct hp = new HistoryProduct(product.getProductName(), product.getBrands(),
                                product.getImageSmallUrl(LocaleHelper.getLanguage(OFFApplication.getInstance())),
                                product.getCode(), product.getQuantity(), product.getNutritionGradeFr());
                            Log.d("syncOldHistory", hp.toString());

                            hp.setLastSeen(historyProduct.getLastSeen());
                            mHistoryProductDao.insertOrReplace(hp);
                        }

                        mContext.getSharedPreferences("prefs", 0).edit().putBoolean("is_old_history_data_synced", true).apply();
                    }

                    @Override
                    public void onFailure(@NonNull Call<State> call, @NonNull Throwable t) {
                        // ignored
                    }
                });
            }
        }).subscribeOn(Schedulers.io()).subscribe();
    }

    public Single<Search> getInfoAddedIncompleteProductsSingle(String contributor, final int page) {
        return api.getInfoAddedIncompleteProductsSingle(contributor, page).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread());
    }

    public void getProductsByManufacturingPlace(final String manufacturingPlace, final int page, final ApiCallbacks.OnStoreCallback onStoreCallback) {
        api.getProductsByManufacturingPlace(manufacturingPlace, page, FIELDS_TO_FETCH_FACETS).enqueue(createStoreCallback(onStoreCallback));
    }

    public Callback<Search> createStoreCallback(ApiCallbacks.OnStoreCallback onStoreCallback) {
        return new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onStoreCallback.onStoreResponse(true, response.body());
                } else {
                    onStoreCallback.onStoreResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onStoreCallback.onStoreResponse(false, null);
            }
        };
    }

    private Callback<Search> createCallback(ApiCallbacks.OnContributorCallback onContributorCallback) {
        return new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onContributorCallback.onContributorResponse(true, response.body());
                } else {
                    onContributorCallback.onContributorResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onContributorCallback.onContributorResponse(false, null);
            }
        };
    }

    /**
     * call API service to return products using Additives
     *
     * @param additive search query for products
     * @param page number of pages
     * @param onAdditiveCallback object of OnAdditiveCallback interface
     */
    public void getProductsByAdditive(final String additive, final int page, final ApiCallbacks.OnAdditiveCallback onAdditiveCallback) {

        api.getProductsByAdditive(additive, page, FIELDS_TO_FETCH_FACETS).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onAdditiveCallback.onAdditiveResponse(true, response.body());
                } else {
                    onAdditiveCallback.onAdditiveResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onAdditiveCallback.onAdditiveResponse(false, null);
            }
        });
    }

    public void getProductsByAllergen(final String allergen, final int page, final ApiCallbacks.OnAllergensCallback onAllergensCallback) {
        api.getProductsByAllergen(allergen, page, FIELDS_TO_FETCH_FACETS).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onAllergensCallback.onAllergensResponse(true, response.body());
                } else {
                    onAllergensCallback.onAllergensResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onAllergensCallback.onAllergensResponse(false, null);
            }
        });
    }

    public void getToBeCompletedProductsByContributor(String contributor, final int page, final ApiCallbacks.OnContributorCallback onContributorCallback) {
        api.getToBeCompletedProductsByContributor(contributor, page).enqueue(createCallback(onContributorCallback));
    }

    public void getPicturesContributedProducts(String contributor, final int page, final ApiCallbacks.OnContributorCallback onContributorCallback) {
        api.getPicturesContributedProducts(contributor, page).enqueue(createCallback(onContributorCallback));
    }

    public void getPicturesContributedIncompleteProducts(String contributor, final int page, final ApiCallbacks.OnContributorCallback onContributorCallback) {
        api.getPicturesContributedIncompleteProducts(contributor, page).enqueue(createCallback(onContributorCallback));
    }

    public void getInfoAddedProducts(String contributor, final int page, final ApiCallbacks.OnContributorCallback onContributorCallback) {
        api.getInfoAddedProducts(contributor, page).enqueue(createCallback(onContributorCallback));
    }

    public Single<Search> getIncompleteProducts(int page) {
        return api.getIncompleteProducts(page, FIELDS_TO_FETCH_FACETS);
    }

    public Single<Search> getProductsByStates(String state, final int page) {
        return api.getProductsByState(state, page, FIELDS_TO_FETCH_FACETS);
    }

    /**
     * OnResponseCall for uploads through notifications
     */
    public void onResponseCallForNotificationPostFunction(Call<State> call, Response<State> response, Context context,
                                                          final ApiCallbacks.OnProductSentCallback productSentCallback,
                                                          SendProduct product) {
        postImages(response, context, productSentCallback, product);
    }

    private void postImages(Response<State> response, Context context, ApiCallbacks.OnProductSentCallback productSentCallback, SendProduct product) {
        if (!response.isSuccessful() || response.body().getStatus() == 0) {

            productSentCallback.onProductSentResponse(false);
            return;
        }

        String imguploadFront = product.getImgupload_front();
        if (StringUtils.isNotEmpty(imguploadFront)) {
            postImg(new ProductImage(product.getBarcode(), FRONT, new File(imguploadFront)), null);
        }

        String imguploadIngredients = product.getImgupload_ingredients();
        if (StringUtils.isNotEmpty(imguploadIngredients)) {
            postImg(new ProductImage(product.getBarcode(), INGREDIENTS, new File(imguploadIngredients)), null);
        }

        String imguploadNutrition = product.getImgupload_nutrition();
        if (StringUtils.isNotBlank(imguploadNutrition)) {
            postImg(new ProductImage(product.getBarcode(), NUTRITION, new File(imguploadNutrition)), null);
        }

        productSentCallback.onProductSentResponse(true);
    }

    /**
     * Post method for upload through notification
     */
    public void postForNotification(final Context context, final SendProduct product, final ApiCallbacks.OnProductSentCallback productSentCallback) {

        if (product.getName().equals("") && product.getBrands().equals("") && product.getQuantity() == null) {
            api.saveProductWithoutNameBrandsAndQuantity(product.getBarcode(), product.getLang(), product.getUserId(), product.getPassword(), getCommentToUpload())
                .enqueue(createNotificationCallback(context, product, productSentCallback));
        } else if (product.getName().equals("") && product.getBrands().equals("")) {
            api
                .saveProductWithoutNameAndBrands(product.getBarcode(), product.getLang(), product.getQuantity(), product.getUserId(), product.getPassword(),
                    getCommentToUpload())
                .enqueue(createNotificationCallback(context, product, productSentCallback));
        } else if (product.getName().equals("") && product.getQuantity() == null) {
            api
                .saveProductWithoutNameAndQuantity(product.getBarcode(), product.getLang(), product.getBrands(), product.getUserId(), product.getPassword(),
                    getCommentToUpload())
                .enqueue(createNotificationCallback(context, product, productSentCallback));
        } else if (product.getBrands().equals("") && product.getQuantity() == null) {
            api
                .saveProductWithoutBrandsAndQuantity(product.getBarcode(), product.getLang(), product.getName(), product.getUserId(), product.getPassword(),
                    getCommentToUpload())
                .enqueue(createNotificationCallback(context, product, productSentCallback));
        } else {
            api.saveProduct(product.getBarcode(), product.getLang(), product.getName(), product.getBrands(), product.getQuantity(), product
                .getUserId(), product.getPassword(), getCommentToUpload()).enqueue(createNotificationCallback(context, product, productSentCallback));
        }
    }

    public Callback<State> createNotificationCallback(Context context, SendProduct product, ApiCallbacks.OnProductSentCallback productSentCallback) {
        return new Callback<State>() {
            @Override
            public void onResponse(@NonNull Call<State> call, @NonNull Response<State> response) {
                onResponseCallForNotificationPostFunction(call, response, context, productSentCallback, product);
            }

            @Override
            public void onFailure(@NonNull Call<State> call, @NonNull Throwable t) {
                productSentCallback.onProductSentResponse(false);
            }
        };
    }
}
