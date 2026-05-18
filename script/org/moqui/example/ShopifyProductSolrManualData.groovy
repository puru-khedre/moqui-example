import java.text.SimpleDateFormat
import java.util.TimeZone
import static org.moqui.util.CollectionUtilities.filterMapListByDate

if (document == null) return
println "DEBUG: RAW DOCUMENT BEFORE MANUAL ENRICHMENT:\n" + groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(document))

// 1. Set fallback values for core fields
document.sku = document.sku ?: document.internalName
document.mainImageUrl = document.mainImageUrl ?: document.detailImageUrl
document.docType = "PRODUCT"
document.identifier = document.productId

// Date formatting for updatedDatetime with milliseconds and UTC Z
def sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
sdf.setTimeZone(TimeZone.getTimeZone("UTC"))
document.updatedDatetime = sdf.format(new Date())

def introDate = document.introductionDate ?: document.createdDate
if (introDate) {
    if (introDate instanceof java.sql.Timestamp || introDate instanceof java.util.Date) {
        // Adjust for server timezone offset to get correct UTC value
        long offset = TimeZone.getDefault().getOffset(introDate.time)
        Date utcDate = new Date(introDate.time - offset)
        def formattedStr = sdf.format(utcDate)
        document.introductionDate = formattedStr.replace('.000Z', 'Z')
    } else {
        // If it's already a String, we can parse and reformat it if it's not in the right format
        try {
            def parsed = java.sql.Timestamp.valueOf(introDate.toString())
            document.introductionDate = sdf.format(parsed).replace(".000Z", "Z")
        } catch (Exception e) {
            document.introductionDate = introDate.toString()
        }
    }
}

// 1b. Casing of isVariant and isVirtual (to string true/false)
def isVar = (document.isVariant == 'Y') ? 'true' : 'false'
def isVirt = (document.isVirtual == 'Y') ? 'true' : 'false'
document.isVariant = isVar
document.isVirtual = isVirt

// 1c. context_field (for isVariant == 'false')
if (isVar == 'false') {
    document.context_field = ["false"]
}

document.title = "Product ${document.productName ?: ''}."
document['docType-identifier'] = "${document.docType}-${document.productId}".toString()
document.spellchecker = document.productName

// 2. Handle Parent Association / Hierarchy
if (isVar == 'true' || isVirt == 'false') {
    def assocList = document.parentAssoc ?: document.ProductAssoc ?: document['Assoc#org.apache.ofbiz.product.product.ProductAssoc']
    if (assocList && assocList.size() > 0) {
        def assoc = assocList.find { it.productIdTo == document.productId }
        if (assoc && assoc.position != null) {
            // Variant position padding to 5 digits like OFBiz (e.g. "00001")
            document.position = assoc.position.toString().padLeft(5, '0')
        }
        if (!assoc) assoc = assocList[0]
        document.groupId = assoc.groupId
        document.groupName = assoc.groupName ?: assoc.groupId
        document.parentProductName = assoc.parentProductName
    } else {
        document.groupId = document.productId
        document.groupName = document.productName ?: document.internalName
    }
} else {
    document.groupId = document.productId
    document.groupName = document.productName ?: document.internalName
}

// 2b. primaryProductCategoryName lookup (Now extracted from Data Document directly)
if (document.primaryProductCategoryName && document.primaryProductCategoryName instanceof Collection) {
    document.primaryProductCategoryName = document.primaryProductCategoryName.first()
}
if (!document.primaryProductCategoryName && document.primaryProductCategoryId) {
    document.primaryProductCategoryName = document.primaryProductCategoryId
}

// 2c. Grab first catalog from primary category for prodCatalogIds
if (document.primaryCatalogIds) {
    if (document.primaryCatalogIds instanceof Collection) {
        document.prodCatalogIds = [document.primaryCatalogIds.first()]
    } else {
        document.prodCatalogIds = [document.primaryCatalogIds]
    }
}

// 3. Feature Hierarchy and productFeatures (Cartesian-join-independent & parent-inherited)
def nowTime = System.currentTimeMillis()
def compareStamp = new java.sql.Timestamp(nowTime)
def allFeatures = []

// A. Filter variant's own active features from the document
if (document.features) {
    filterMapListByDate(document.features, 'fromDate', 'thruDate', compareStamp)
    allFeatures.addAll(document.features)
}

// B. Parent features lookup from Data Document directly
def assocList = document.parentAssoc ?: document.ProductAssoc ?: document['Assoc#org.apache.ofbiz.product.product.ProductAssoc']
if (assocList) {
    def assoc = assocList.find { it.productIdTo == document.productId }
    if (assoc) {
        def parentFeats = []
        // Look up features using the parent associations from Data Document directly
        def featIdList = document.parentFeatureTypeId
        def featDescList = document.parentFeatureDescription
        def featTypeDescList = document.parentFeatureTypeDescription
        def featFromList = document.parentFeatureFromDate
        def featThruList = document.parentFeatureThruDate
        if (featIdList instanceof Collection && featIdList.size() > 0) {
            for (int i = 0; i < featIdList.size(); i++) {
                parentFeats << [
                    featureTypeId: featIdList[i],
                    featureDescription: (featDescList instanceof Collection && featDescList.size() > i) ? featDescList[i] : null,
                    featureTypeDescription: (featTypeDescList instanceof Collection && featTypeDescList.size() > i) ? featTypeDescList[i] : null,
                    fromDate: (featFromList instanceof Collection && featFromList.size() > i) ? featFromList[i] : null,
                    thruDate: (featThruList instanceof Collection && featThruList.size() > i) ? featThruList[i] : null
                ]
            }
        }
        
        if (parentFeats) {
            filterMapListByDate(parentFeats, 'fromDate', 'thruDate', compareStamp)
            allFeatures.addAll(parentFeats)
        }
    }
}

// C. Deduplicate the combined list
def featureValues = []
def solrFeatures = []
def featHierarchies = []

for (def feat in allFeatures) {
    def featDesc = feat.featureDescription ?: feat.description
    if (featDesc) {
        featureValues.add(featDesc)
    }
    
    def featTypeId = feat.featureTypeId ?: feat.productFeatureTypeId
    if (featTypeId) {
        featHierarchies.add("0/${featTypeId}/".toString())
        if (featDesc) {
            featHierarchies.add("1/${featTypeId}/${featDesc}/".toString())
        }
    }
    
    // Use featureTypeDescription from DataDocument instead of hardcoding 'feature'
    String typeDesc = feat.featureTypeDescription ?: "feature"
    // e.g. "Color/Black"
    if (featDesc) {
        solrFeatures.add("${typeDesc}/${featDesc}".toString())
    }
}

document.features = featureValues.unique()
document.productFeatures = solrFeatures.unique()
document.featureHierarchy = featHierarchies.unique()

// 4. Flatten Good Identifications and Lookup ShopifyShopProduct
def idents = []
// 5. Mappings for Identifications (GoodIdentifications & ShopifyShopProduct)
if (document.identifications) {
    for (def ident in document.identifications) {
        if (ident.idType == 'UPCA') {
            document.upc = ident.idValue
        }
    }
}

// Map shopId and shopifyProductId if present
if (document.shopifyShopProducts) {
    for (def sp in document.shopifyShopProducts) {
        if (sp.shopifyProductId) {
            document.shopifyProductId = sp.shopifyProductId
            document.shopId = sp.shopId
            break // Fetch the first one if there are multiple
        }
    }
}

// Fallback for upc missing
if (!document.upc) {
    document.upc = ''
}

if (document.identifications) {
    for (def ident in document.identifications) {
        if (ident.idType && ident.idValue) {
            idents.add("${ident.idType}/${ident.idValue}".toString())
        }
    }
}

// Format ShopifyShopProduct into GoodIdentifications array without doing a DB query
if (document.shopifyShopProducts) {
    for (def sp in document.shopifyShopProducts) {
        if (sp.shopId && sp.shopifyProductId) {
            idents.add("ShopifyShopProduct/${sp.shopId}/${sp.shopifyProductId}".toString())
        }
    }
}

document.goodIdentifications = idents.unique()

// 4b. keywordSearchText construction
def keywordSearchText = []
if (document.productId) keywordSearchText.add(document.productId.toString())
if (document.productName) keywordSearchText.add(document.productName.toString())
if (document.internalName) keywordSearchText.add(document.internalName.toString())

if (isVar == 'true') {
    if (document.parentProductName) keywordSearchText.add(document.parentProductName.toString())
    if (document.groupName) keywordSearchText.add(document.groupName.toString())
    if (document.groupId) keywordSearchText.add(document.groupId.toString())
} else {
    if (document.productId) keywordSearchText.add(document.productId.toString())
}
if (document.upc) keywordSearchText.add(document.upc.toString())
document.keywordSearchText = keywordSearchText

// 5. Mappings for Prices to dynamic keys e.g. DEFAULT_PRICE_PURCHASE_USD_STORE_GROUP_price
if (document.prices) {
    for (def pr in document.prices) {
        if (pr.price != null) {
            def type = pr.type ?: 'DEFAULT_PRICE'
            def purpose = pr.purpose ?: 'PURCHASE'
            def currency = pr.currency ?: 'USD'
            def storeGroup = pr.storeGroup ?: '_NA_'
            
            def priceKey = "${type}_${purpose}_${currency}_${storeGroup}_price"
            document[priceKey] = pr.price
            
            if (type == 'LIST_PRICE') {
                def basePriceKey = "BASE_PRICE_${purpose}_${currency}_${storeGroup}_price"
                document[basePriceKey] = pr.price
            }
        }
    }
}

// 6. Mappings for stores & catalogs (with date filtering using CollectionUtilities)
def storeIds = []
def catalogIds = []
def catalogCategoryTypeIds = []
def cats = document.categories
if (cats) {
    filterMapListByDate(cats, "categoryFromDate", "categoryThruDate", compareStamp)
    for (def cat in cats) {
        def catalogList = cat.ProdCatalogCategory
        if (catalogList) {
            filterMapListByDate(catalogList, "catalogFromDate", "catalogThruDate", compareStamp)
            for (def catalog in catalogList) {
                if (catalog.prodCatalogIds) {
                    catalogIds.add(catalog.prodCatalogIds)
                }
                if (catalog.prodCatalogCategoryTypeIds) {
                    catalogCategoryTypeIds.add(catalog.prodCatalogCategoryTypeIds)
                }
                def storeList = catalog.ProductStoreCatalog
                if (storeList) {
                    filterMapListByDate(storeList, "storeFromDate", "storeThruDate", compareStamp)
                    for (def store in storeList) {
                        if (store.productStoreIds) {
                            storeIds.add(store.productStoreIds)
                        }
                    }
                }
            }
        }
    }
}
document.productStoreIds = storeIds.unique()
document.productStoreIds_s = storeIds.unique()
document.prodCatalogIds = catalogIds.unique()
document.prodCatalogCategoryTypeIds = catalogCategoryTypeIds

// 7. Keywords / Tags
def tags = []
def productTypes = []
if (document.keywords) {
    for (def kw in document.keywords) {
        if (kw.keywordType == 'KWT_TAG' && kw.keyword) {
            tags.add(kw.keyword)
        } else if (kw.keywordType == 'KWT_PROD_TYPE' && kw.keyword) {
            productTypes.add(kw.keyword)
        }
    }
}
document.tags = tags
document.productType = productTypes

// 8. Clean up intermediate nested blocks
document.remove('prices')
document.remove('keywords')
document.remove('features')
document.remove('identifications')
document.remove('categories')
document.remove('Assoc#org.apache.ofbiz.product.product.ProductAssoc')
document.remove('ProductAssoc')
document.remove('parentAssoc')
document.remove('primaryProductCategoryId')
