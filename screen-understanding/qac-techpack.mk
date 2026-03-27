# Include QAC Screen Understanding modules
ifneq (,$(wildcard vendor/qcom/opensource/qac-services/screen-understanding))
include vendor/qcom/opensource/qac-services/screen-understanding/qac_services_vendor_product.mk
endif

# Add QACS phony targets
#.PHONY: qac_tp
#
#qac_tp: $(QAC_SU)
#
#$(warning "QAC Techpack configuration QAC_SU = $(QAC_SU)")