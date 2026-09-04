package name.abuchen.portfolio.ui.wizards.security;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.ParsePosition;

import org.eclipse.core.databinding.UpdateValueStrategy;
import org.eclipse.core.databinding.beans.typed.BeanProperties;
import org.eclipse.core.databinding.conversion.IConverter;
import org.eclipse.core.databinding.observable.value.IObservableValue;
import org.eclipse.jface.databinding.swt.typed.WidgetProperties;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import name.abuchen.portfolio.ui.Images;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.util.BindingHelper;
import name.abuchen.portfolio.ui.util.IValidatingConverter;
import name.abuchen.portfolio.ui.util.SWTHelper;
import name.abuchen.portfolio.ui.util.swt.ControlDecoration;
import name.abuchen.portfolio.ui.util.text.DecimalKeypadSupport;

public class SecurityMasterDataPage extends AbstractPage
{
    private final EditSecurityModel model;
    private final BindingHelper bindings;

    protected SecurityMasterDataPage(EditSecurityModel model, BindingHelper bindings)
    {
        this.model = model;
        this.bindings = bindings;

        setTitle(Messages.EditWizardMasterDataTitle);
    }

    @Override
    public void createControl(Composite parent)
    {
        Composite container = new Composite(parent, SWT.NULL);
        setControl(container);
        GridLayoutFactory.fillDefaults().numColumns(2).margins(5, 5).applyTo(container);

        boolean isExchangeRate = model.getSecurity().isExchangeRate();

        Control currencyCode = bindings.bindCurrencyCodeCombo(container, Messages.ColumnCurrency, "currencyCode", //$NON-NLS-1$
                        !isExchangeRate);
        if (model.getSecurity().hasTransactions(model.getClient()))
        {
            currencyCode.setEnabled(false);

            // empty cell
            new Label(container, SWT.NONE).setText(""); //$NON-NLS-1$

            Composite info = new Composite(container, SWT.NONE);
            info.setLayout(new RowLayout());

            Label l = new Label(info, SWT.NONE);
            l.setImage(Images.INFO.image());

            l = new Label(info, SWT.NONE);
            l.setText(Messages.MsgInfoChangingCurrencyNotPossible);

        }

        if (isExchangeRate)
        {
            Control targetCurrencyCode = bindings.bindCurrencyCodeCombo(container, Messages.ColumnTargetCurrency,
                            "targetCurrencyCode", false); //$NON-NLS-1$
            targetCurrencyCode.setToolTipText(Messages.ColumnTargetCurrencyToolTip);
        }

        if (!isExchangeRate)
        {
            bindings.bindISINInput(container, Messages.ColumnISIN, "isin", 30); //$NON-NLS-1$
        }

        bindings.bindStringInput(container, Messages.ColumnTicker, "tickerSymbol", SWT.NONE, 30); //$NON-NLS-1$

        if (!isExchangeRate)
        {
            bindings.bindStringInput(container, Messages.ColumnWKN, "wkn", SWT.NONE, 30); //$NON-NLS-1$

            ComboViewer calendar = bindings.bindCalendarCombo(container, Messages.LabelSecurityCalendar, "calendar"); //$NON-NLS-1$
            calendar.getCombo().setToolTipText(Messages.LabelSecurityCalendarToolTip);

            bindWithholdingTaxRate(container);
        }

        Control control = bindings.bindBooleanInput(container, Messages.ColumnRetired, "retired"); //$NON-NLS-1$

        int margin = 2;
        Image info = Images.INFO.image();
        Rectangle bounds = info.getBounds();

        GridDataFactory.fillDefaults().indent(bounds.width + margin, 0).applyTo(control);

        ControlDecoration deco = new ControlDecoration(control, SWT.CENTER | SWT.LEFT);
        deco.setDescriptionText(Messages.MsgInfoRetiredSecurities);
        deco.setImage(info);
        deco.setMarginWidth(margin);
        deco.show();

        Text valueNote = bindings.bindStringInput(container, Messages.ColumnNote, "note", //$NON-NLS-1$
                        SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.WRAP, SWT.DEFAULT);
        GridDataFactory.fillDefaults().grab(true, true).hint(SWT.DEFAULT, SWTHelper.lineHeight(valueNote) * 4)
                        .applyTo(valueNote);
    }

    private void bindWithholdingTaxRate(Composite container)
    {
        Label label = new Label(container, SWT.NONE);
        label.setText(Messages.ColumnWithholdingTaxRate);

        Text value = new Text(container, SWT.BORDER | SWT.RIGHT);
        value.setToolTipText(Messages.ColumnWithholdingTaxRate_Description);
        DecimalKeypadSupport.configure(value);
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.FILL)
                        .hint((int) Math.round(15 * SWTHelper.getAverageCharWidth(value)), SWT.DEFAULT).applyTo(value);

        IValidatingConverter<String, BigDecimal> inputConverter = new WithholdingTaxRateInputConverter();
        IObservableValue<String> target = WidgetProperties.text(SWT.Modify).observe(value);
        IObservableValue<BigDecimal> observable = BeanProperties.value("withholdingTaxRate", BigDecimal.class) //$NON-NLS-1$
                        .observe(model);

        bindings.getBindingContext().bindValue(target, observable,
                        new UpdateValueStrategy<String, BigDecimal>().setAfterGetValidator(inputConverter)
                                        .setConverter(inputConverter),
                        new UpdateValueStrategy<BigDecimal, String>().setConverter(new WithholdingTaxRateOutputConverter()));
    }

    private static final class WithholdingTaxRateInputConverter implements IValidatingConverter<String, BigDecimal>
    {
        private final DecimalFormat format = new DecimalFormat("#,##0.###"); //$NON-NLS-1$

        private WithholdingTaxRateInputConverter()
        {
            format.setParseBigDecimal(true);
        }

        @Override
        public Object getFromType()
        {
            return String.class;
        }

        @Override
        public Object getToType()
        {
            return BigDecimal.class;
        }

        @Override
        public BigDecimal convert(String input)
        {
            String value = input.trim();
            if (value.isEmpty())
                return null;

            ParsePosition position = new ParsePosition(0);
            BigDecimal percentage = (BigDecimal) format.parse(value, position);
            if (percentage == null || position.getIndex() != value.length() || percentage.signum() < 0
                            || percentage.compareTo(BigDecimal.valueOf(100)) > 0)
                throw new IllegalArgumentException(String.format(Messages.CellEditor_NotANumber, value));

            return percentage.movePointLeft(2);
        }
    }

    private static final class WithholdingTaxRateOutputConverter implements IConverter<BigDecimal, String>
    {
        private final DecimalFormat format = new DecimalFormat("#,##0.###"); //$NON-NLS-1$

        @Override
        public Object getFromType()
        {
            return BigDecimal.class;
        }

        @Override
        public Object getToType()
        {
            return String.class;
        }

        @Override
        public String convert(BigDecimal rate)
        {
            return rate != null ? format.format(rate.movePointRight(2)) : ""; //$NON-NLS-1$
        }
    }
}
