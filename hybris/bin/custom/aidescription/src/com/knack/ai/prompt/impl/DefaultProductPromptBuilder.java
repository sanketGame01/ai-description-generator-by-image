package com.knack.ai.prompt.impl;

import de.hybris.platform.category.model.CategoryModel;
import de.hybris.platform.classification.ClassificationService;
import de.hybris.platform.classification.features.Feature;
import de.hybris.platform.classification.features.FeatureList;
import de.hybris.platform.classification.features.FeatureValue;
import de.hybris.platform.core.model.product.ProductModel;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.knack.ai.prompt.ProductPromptBuilder;


/**
 * Default {@link ProductPromptBuilder}. Uses only real product facts and scales the requested length to the amount
 * of data available.
 */
public class DefaultProductPromptBuilder implements ProductPromptBuilder
{
	static final String IGNORED_FEATURE = "Source data-sheet";
	static final int DESCRIPTION_FACT_WEIGHT = 3;
	static final int RICH_DATA_THRESHOLD = 6;
	private static final Set<String> NO_UNIT_SYMBOLS = Set.of(".", "%");
	/** Text made only of digits and separators, such as "12 X 2 X 10"; a unit still applies to it. */
	private static final Pattern NUMERIC_TEXT = Pattern.compile("^[0-9][0-9.,\\s xX\u00d7*/-]*$");

	private ClassificationService classificationService;

	@Override
	public String buildPrompt(final ProductModel product)
	{
		final String brand = product.getManufacturerName();
		final String partNumber = product.getManufacturerAID();
		final List<String> specs = getSpecifications(product);
		final String existing = existingDescription(product);
		final int facts = specs.size() + (existing.isEmpty() ? 0 : DESCRIPTION_FACT_WEIGHT);

		final StringBuilder prompt = new StringBuilder();
		prompt.append("Write an SEO-friendly product description for an online store.\n");
		prompt.append(facts >= RICH_DATA_THRESHOLD ? "Length: 80-120 words in 2 short paragraphs.\n"
				: "Length: 40-60 words in 1 paragraph.\n");
		prompt.append("Use only the facts below. Do not add claims about quality, durability, compatibility, standards, "
				+ "or use cases that are not stated.\n");
		prompt.append("Do not use subjective words such as lightweight, portable, reliable, sturdy, sleek, perfect, ideal "
				+ "or premium unless that exact word appears in the facts.\n");
		prompt.append("Do not say who the product is for or how to use it unless the facts state it.\n");
		prompt.append("If you are unsure what a specification means, state it plainly without interpreting it.\n");
		prompt.append("Return plain text in flowing sentences, no HTML, no headings, no bullet points.\n");
		prompt.append("If a value in Specifications conflicts with the Existing description, use the Existing "
				+ "description value.\n\n");

		prompt.append("Verified facts:\n");
		prompt.append("Product: ").append(product.getName()).append('\n');
		prompt.append("Brand: ").append(isBlank(brand) ? "n/a" : brand).append('\n');
		if (!isBlank(partNumber))
		{
			prompt.append("Part number: ").append(partNumber.trim()).append('\n');
		}
		prompt.append("Category: ").append(buildCategories(product, brand)).append('\n');
		prompt.append("Specifications:\n");
		prompt.append(specs.isEmpty() ? "- none" : specs.stream().map(s -> "- " + s).collect(Collectors.joining("\n")));
		if (!existing.isEmpty())
		{
			prompt.append("\nExisting description (facts only): ").append(existing);
		}
		return prompt.toString();
	}

	@Override
	public int countFacts(final ProductModel product)
	{
		return getSpecifications(product).size() + (existingDescription(product).isEmpty() ? 0 : DESCRIPTION_FACT_WEIGHT);
	}

	@Override
	public List<String> getSpecifications(final ProductModel product)
	{
		final FeatureList featureList = classificationService.getFeatures(product);
		if (featureList == null || featureList.getFeatures() == null)
		{
			return List.of();
		}
		return featureList.getFeatures().stream()
				.filter(f -> !IGNORED_FEATURE.equals(f.getName()))
				.filter(f -> f.getValues() != null && !f.getValues().isEmpty())
				.map(this::formatFeature)
				.collect(Collectors.toList());
	}

	protected String existingDescription(final ProductModel product)
	{
		final String description = product.getDescription(Locale.ENGLISH);
		if (description == null)
		{
			return "";
		}
		return description.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
	}

	protected String buildCategories(final ProductModel product, final String brand)
	{
		final Collection<CategoryModel> categories = product.getSupercategories();
		if (categories == null)
		{
			return "";
		}
		return categories.stream()
				.map(CategoryModel::getName)
				.filter(Objects::nonNull)
				.filter(name -> !name.equals(brand))
				.collect(Collectors.joining(", "));
	}

	protected String formatFeature(final Feature feature)
	{
		return feature.getName() + ": " + feature.getValues().stream().map(this::formatValue).collect(Collectors.joining(", "));
	}

	protected String formatValue(final FeatureValue featureValue)
	{
		final Object raw = featureValue.getValue();
		final String value;
		if (raw instanceof Number)
		{
			value = new BigDecimal(raw.toString()).stripTrailingZeros().toPlainString();
		}
		else if (raw != null && NUMERIC_TEXT.matcher(raw.toString().trim()).matches())
		{
			value = raw.toString().trim();
		}
		else
		{
			return String.valueOf(raw);
		}

		final String symbol = featureValue.getUnit() == null || featureValue.getUnit().getSymbol() == null ? ""
				: featureValue.getUnit().getSymbol().trim();

		return symbol.isEmpty() || NO_UNIT_SYMBOLS.contains(symbol) ? value : value + " " + symbol;
	}

	private static boolean isBlank(final String value)
	{
		return value == null || value.isBlank();
	}

	public void setClassificationService(final ClassificationService classificationService)
	{
		this.classificationService = classificationService;
	}
}

