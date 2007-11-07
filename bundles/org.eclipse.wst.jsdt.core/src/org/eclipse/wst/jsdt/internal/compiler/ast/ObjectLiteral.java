package org.eclipse.wst.jsdt.internal.compiler.ast;

import org.eclipse.wst.jsdt.internal.compiler.ASTVisitor;
import org.eclipse.wst.jsdt.internal.compiler.flow.FlowContext;
import org.eclipse.wst.jsdt.internal.compiler.flow.FlowInfo;
import org.eclipse.wst.jsdt.internal.compiler.impl.Constant;
import org.eclipse.wst.jsdt.internal.compiler.lookup.BlockScope;
import org.eclipse.wst.jsdt.internal.compiler.lookup.TypeBinding;


public class ObjectLiteral extends Expression {

	public ObjectLiteralField [] fields;

	public StringBuffer printExpression(int indent, StringBuffer output) {
		if (fields==null || fields.length==0)
		{
			output.append("{}"); //$NON-NLS-1$
		}
		else
		{
			output.append("{\n"); //$NON-NLS-1$
			printIndent(indent+1, output);
			for (int i = 0; i < fields.length; i++) {
				if (i>0)
				{
					output.append(",\n"); //$NON-NLS-1$
					printIndent(indent+1, output);
				}
				fields[i].printExpression(indent, output);
			}
			output.append("\n"); //$NON-NLS-1$
			printIndent(indent, output);
			output.append("}"); //$NON-NLS-1$
		}
		return output;
	}
	public void traverse(ASTVisitor visitor, BlockScope scope) {
		if (visitor.visit(this, scope)) {
			if (fields!=null)
				for (int i = 0; i < fields.length; i++) {
					fields[i].traverse(visitor, scope);
				}
		}
		visitor.endVisit(this, scope);
	}


	public TypeBinding resolveType(BlockScope scope) {
		this.constant=Constant.NotAConstant;
		if (this.fields!=null)
			for (int i = 0; i < this.fields.length; i++) {
				this.fields[i].resolveType(scope);
			}
		return TypeBinding.ANY;
	}

	public int nullStatus(FlowInfo flowInfo) {
			return FlowInfo.NON_NULL; // constant expression cannot be null
	}

	public FlowInfo analyseCode(
			BlockScope classScope,
			FlowContext initializationContext,
			FlowInfo flowInfo) {
		if (this.fields!=null)
			for (int i = 0; i < this.fields.length; i++) {
				flowInfo=this.fields[i].analyseCode(classScope,initializationContext, flowInfo);
			}

		return flowInfo;
	}
}
